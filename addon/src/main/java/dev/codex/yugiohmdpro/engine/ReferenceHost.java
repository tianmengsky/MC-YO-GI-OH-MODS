package dev.codex.yugiohmdpro.engine;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Matched mdpromod host + core + database + scripts. One process owns each duel's callbacks. */
public final class ReferenceHost implements AutoCloseable {
    /** Windows keeps the original win-x64 package; Linux servers (Termux/arm64) use the ported one. */
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private static final boolean AARCH64 = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).matches("aarch64|arm64");
    private final Path root;
    private final Set<Session> sessions = new HashSet<>();
    private final Map<Integer,byte[]> declarationCards = new HashMap<>();
    public ReferenceHost(Path directory) throws Exception {
        root=directory.toAbsolutePath();
        install();
        // Generated from the reference database, solely for the GUI declaration search predicate.
        try(var in=new DataInputStream(Files.newInputStream(root.resolve("declaration-cards.bin")))) {
            if(in.readInt()!=0x4D445031)throw new IOException("Invalid reference card index");
            int n=in.readInt();if(n<1 || n>100000)throw new IOException("Invalid reference card count");
            for(int i=0;i<n;i++){int code=in.readInt();byte[] data=in.readNBytes(76);if(data.length!=76)throw new EOFException();declarationCards.put(code,data);}
        }
    }
    private void install() throws Exception {
        Files.createDirectories(root);
        if(LINUX && !AARCH64)throw new IOException("Linux 版示例宿主只提供 arm64（当前 " + System.getProperty("os.arch") + "）");
        String packaged=LINUX?"/data/yugioh_mdpro_addon/reference-host-linux.zip":"/data/yugioh_mdpro_addon/reference-host.zip";
        try(InputStream stream=ReferenceHost.class.getResourceAsStream(packaged)) {
            if(stream==null)throw new IOException("缺少示例决斗宿主资源，请安装完整构建");
            // ZIP is shipped with the addon, and every installed file is verified against its manifest.
            Map<String,byte[]> entries=new LinkedHashMap<>();
            try(var zip=new ZipInputStream(stream)) {ZipEntry e;while((e=zip.getNextEntry())!=null)if(!e.isDirectory())entries.put(e.getName(),zip.readAllBytes());}
            JsonObject manifest=JsonParser.parseString(new String(Objects.requireNonNull(entries.get("manifest.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            for(JsonElement item:manifest.getAsJsonArray("files")) {
                JsonObject entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                Path target=root.resolve(name).normalize();if(!target.startsWith(root))throw new IOException("Invalid host path");
                byte[] bytes=Objects.requireNonNull(entries.get(name));String expected=entry.get("sha256").getAsString();
                if(!hash(bytes).equals(expected))throw new IOException("Invalid packaged reference file: "+name);
                if(Files.isRegularFile(target) && hash(Files.readAllBytes(target)).equals(expected))continue;
                Files.createDirectories(target.getParent());Path temp=target.resolveSibling(target.getFileName()+".install");
                Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(root.resolve("manifest.json"),entries.get("manifest.json"));
        }
        if(LINUX)try {
            // The launcher is a shell script; the JVM writes 0644 and cannot exec that.
            Files.setPosixFilePermissions(root.resolve("duelhost/adapter"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"));
        } catch(Exception ignored) {}
    }
    private static String hash(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    public Set<Integer> declarableCodes(int[] program) {
        Set<Integer> result=new TreeSet<>();declarationCards.forEach((code,data)->{if(CardDeclaration.accepts(data,program))result.add(code);});return result;
    }
    public Session create(int seed,List<Integer> main0,List<Integer> extra0,List<Integer> main1,List<Integer> extra1) throws IOException {
        for(var list:List.of(main0,extra0,main1,extra1))for(int code:list)if(!declarationCards.containsKey(code))throw new IOException("示例内核缺少卡片："+code);
        Session session=new Session(seed,main0,extra0,main1,extra1);sessions.add(session);return session;
    }
    public record Batch(int status,byte[] bytes,JsonObject prompt) {
        public boolean waiting(){return prompt!=null;}
        public boolean ended(){return status==0x20000000;}
    }
    public final class Session implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<JsonObject> events=new LinkedBlockingQueue<>(4096);
        private final Path work;
        private final int seed;
        private final Map<String,byte[]> queries=new HashMap<>();
        private byte[] field=new byte[0];
        private volatile boolean closed;
        private boolean waiting,started,ended;
        private final long launched=System.nanoTime();
        private long responding=launched;
        private Session(int seed,List<Integer> a,List<Integer> ax,List<Integer> b,List<Integer> bx) throws IOException {
            this.seed=seed;work=root.resolve("sessions/"+UUID.randomUUID());Files.createDirectories(work);
            deck(work.resolve("0.ydk"),a,ax);deck(work.resolve("1.ydk"),b,bx);
            var builder=new ProcessBuilder(root.resolve(LINUX?"duelhost/adapter":"duelhost/adapter.exe").toString(),"--root",root.resolve("runtime").toString())
                    .directory(root.resolve("runtime").toFile()).redirectErrorStream(true);
            if(!LINUX)builder.environment().put("DOTNET_ROOT",root.resolve("duelhost").toString());
            builder.environment().put("TEMP",work.toString());builder.environment().put("TMP",work.toString());
            process=builder.start();input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
            Thread reader=new Thread(this::read,"mdpro-reference-"+process.pid());reader.setDaemon(true);reader.start();
        }
        private void deck(Path file,List<Integer> main,List<Integer> extra) throws IOException {
            StringBuilder text=new StringBuilder("#main\n");main.forEach(c->text.append(c).append('\n'));text.append("#extra\n");extra.forEach(c->text.append(c).append('\n'));text.append("!side\n");Files.writeString(file,text);
        }
        private void read() {
            try(var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                var trace=Files.newBufferedWriter(work.resolve("host.jsonl"),StandardCharsets.UTF_8)) {
                String line;while(!closed && (line=reader.readLine())!=null) {
                    trace.write(line);trace.newLine();trace.flush();
                    try {
                        JsonObject event=JsonParser.parseString(line).getAsJsonObject();
                        if(!events.offer(event))throw new IOException("Reference host event queue overflow");
                    } catch(JsonParseException | IllegalStateException ex) {
                        // The bundled Windbot initializer writes plain diagnostic lines even in PvP.
                        if(line.startsWith("["))continue;
                        throw new IOException("Reference host emitted invalid JSON: "+line.substring(0,Math.min(300,line.length())),ex);
                    }
                }
            } catch(Exception ex) {if(!closed)error(ex.getMessage());}
            finally {if(!closed)error("示例决斗宿主意外退出");}
        }
        private void error(String text){JsonObject e=new JsonObject();e.addProperty("type","error");e.addProperty("message",text);events.offer(e);}
        public void start(boolean deterministic) throws IOException {
            start(deterministic,-1);
        }
        public void start(boolean deterministic,int aiSeat) throws IOException {
            if(started)throw new IllegalStateException("Already started");started=true;
            JsonObject cmd=new JsonObject();cmd.addProperty("cmd","start");cmd.addProperty("seed",Integer.toUnsignedLong(seed));
            cmd.addProperty("deck0",work.resolve("0.ydk").toString());cmd.addProperty("deck1",work.resolve("1.ydk").toString());
            cmd.addProperty("ai",aiSeat==0 || aiSeat==1);
            if(aiSeat==0 || aiSeat==1){cmd.addProperty("aiSeat",aiSeat);cmd.addProperty("aiDeck","AI_Test");cmd.addProperty("aiDeckFile",work.resolve(aiSeat+".ydk").toString());cmd.addProperty("aiDialog","default");}
            cmd.addProperty("auto",false);cmd.addProperty("autoPassChain",false);cmd.addProperty("timing","default");send(cmd);
        }
        public Batch process() {
            if(closed || waiting)throw new IllegalStateException("Reference session not ready");
            JsonObject e;
            while((e=events.poll())!=null) {
                String type=e.get("type").getAsString();
                switch(type) {
                    case "error" -> throw new IllegalStateException(e.get("message").getAsString(),
                            e.has("stack") ? new IllegalStateException(e.get("stack").getAsString()) : null);
                    case "warn" -> throw new IllegalStateException("示例宿主警告："+e);
                    case "prompt" -> {
                        snapshot(e);
                        waiting=true;return new Batch(0x10000000,HexFormat.of().parseHex(e.get("raw").getAsString()),e);
                    }
                    case "snapshot" -> snapshot(e);
                    case "message" -> {return new Batch(0,HexFormat.of().parseHex(e.get("hex").getAsString()),null);}
                    case "ended" -> {ended=true;return new Batch(0x20000000,new byte[0],null);}
                }
            }
            if(!ended && System.nanoTime()-responding>TimeUnit.SECONDS.toNanos(60))throw new IllegalStateException("示例决斗宿主应答超时");
            return new Batch(0,new byte[0],null);
        }
        private void snapshot(JsonObject e) {
            if(!e.has("board") || !e.has("field"))throw new IllegalStateException("Reference snapshot missing");
            field=HexFormat.of().parseHex(e.get("field").getAsString());queries.clear();
            for(JsonElement item:e.getAsJsonArray("board")) {JsonObject q=item.getAsJsonObject();String key=q.get("player").getAsInt()+":"+q.get("location").getAsInt();
                queries.put(key+":"+0x60033B,HexFormat.of().parseHex(q.get("cards").getAsString()));
                queries.put(key+":"+0x20003,HexFormat.of().parseHex(q.get("counters").getAsString()));
            }
        }
        public byte[] query(int player,int location){return query(player,location,0x60033B);}
        public byte[] query(int player,int location,int flags){return queries.getOrDefault(player+":"+location+":"+flags,new byte[0]);}
        public byte[] fieldInfo(){return field;}
        public boolean waiting(){return !closed && waiting;}
        /**
         * Identity of everything the duel screen can render: life points, every zone query and the
         * counters. Two states with the same fingerprint look identical to a player, so the pacer
         * folds the second one instead of spending an animation beat on it. A hash collision could
         * only merge two visually different frames; it can never change a rule outcome.
         */
        public long stateFingerprint(){
            long h=1125899906842597L;
            h=31*h+field.length;
            for(byte b:field)h=31*h+b;
            for(var e:new java.util.TreeMap<>(queries).entrySet()){
                h=97*h+e.getKey().hashCode();
                byte[] value=e.getValue();h=31*h+value.length;
                for(byte b:value)h=31*h+b;
            }
            return h;
        }
        /** Host events already emitted and not yet consumed: backpressure evidence for the pacer. */
        public int queuedEvents(){return events.size();}
        public void respond(int value) {JsonObject cmd=new JsonObject();cmd.addProperty("cmd","response");cmd.addProperty("value",value);reply(cmd);}
        public void respond(byte[] value) {if(value.length>64 || value.length==0)throw new IllegalArgumentException("Invalid response length");JsonObject cmd=new JsonObject();cmd.addProperty("cmd","responseBytes");cmd.addProperty("hex",HexFormat.of().formatHex(value));reply(cmd);}
        private void reply(JsonObject cmd){if(!waiting())throw new IllegalStateException("No pending reference request");try{send(cmd);waiting=false;responding=System.nanoTime();}catch(IOException ex){throw new UncheckedIOException(ex);}}
        private void send(JsonObject cmd) throws IOException {input.write(cmd.toString());input.newLine();input.flush();}
        @Override public void close(){if(closed)return;closed=true;waiting=false;try{input.close();}catch(IOException ignored){}process.destroy();if(process.isAlive())process.destroyForcibly();sessions.remove(this);events.clear();}
    }
    @Override public void close(){for(Session s:List.copyOf(sessions))s.close();}
}
