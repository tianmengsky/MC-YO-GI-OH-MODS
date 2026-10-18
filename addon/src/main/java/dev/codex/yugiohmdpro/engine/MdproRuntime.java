package dev.codex.yugiohmdpro.engine;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** One process-wide callback owner. All access, including native process(), is serialized. */
public final class MdproRuntime implements AutoCloseable {
    private static MdproRuntime owner;
    public static final String CORE_SHA256 = "d0852134e61bb4b97d770156e8bd4e3f4c57687956e6b69ad690b0de7f05f42d";
    private static final String CARDS_SHA256 = "bd8a6d846c4ed2e7b5f16539e1f9f4042e05f01b10b095617978727376f06f4e";
    private static final String SCRIPTS_SHA256 = "ddd8346886f8d46ca4516361629c591547c942e9e6759a25280813784e2c9a75";
    private final OcgApi api;
    private final ZipFile scripts;
    private final Map<Integer, byte[]> cards = new HashMap<>();
    private final Map<String, Memory> scriptMemory = new HashMap<>();
    private final Set<Session> sessions = new HashSet<>();
    // Percy treats every database alias as an artwork alias when selecting Lua.
    // These four aliases describe a permanent rule name, although their effects differ.
    private static final Set<Integer> HARPIE_RULE_NAMES=Set.of(27927359,54415063,80316585,91932350);
    private final List<String> errors = new ArrayList<>();
    // Strong references must outlive every duel and every native call.
    private final OcgApi.ScriptReader scriptReader = this::readScript;
    private final OcgApi.CardReader cardReader = this::readCard;
    private final OcgApi.LogHandler logHandler = this::log;
    private final Memory logBuffer = new Memory(0x40000);
    private boolean closed;

    public MdproRuntime(Path root) throws Exception {
        synchronized (MdproRuntime.class) {
            if (owner != null) throw new IllegalStateException("ocgcore callbacks already have a runtime owner");
        Path dll = root.resolve("ocgcore.dll");
        String hash = fileHash(dll);
        if (!hash.equals(CORE_SHA256)) throw new IOException("Unverified ocgcore SHA-256: " + hash);
        verifyFile(root.resolve("cards.bin"), CARDS_SHA256);
        verifyFile(root.resolve("script.zip"), SCRIPTS_SHA256);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(root.resolve("cards.bin")))) {
            if (input.readInt() != 0x4D445031) throw new IOException("Invalid MDPro card data header");
            int count = input.readInt();
            if (count < 1 || count > 100000) throw new IOException("Invalid card count");
            for (int i = 0; i < count; i++) {
                int code = input.readInt();
                byte[] data = input.readNBytes(76);
                if (data.length != 76) throw new EOFException("Truncated card data");
                if(HARPIE_RULE_NAMES.contains(code))java.util.Arrays.fill(data,4,8,(byte)0);
                cards.put(code, data);
            }
        }
        scripts = new ZipFile(root.resolve("script.zip").toFile());
        api = Native.load(dll.toAbsolutePath().toString(), OcgApi.class);
        api.set_script_reader(scriptReader);
        api.set_card_reader(cardReader);
        api.set_message_handler(logHandler);
        owner = this;
        }
    }

    private static void verifyFile(Path path, String expected) throws Exception {
        String actual = fileHash(path);
        if (!actual.equals(expected)) throw new IOException("Unverified data file: " + path.getFileName());
    }
    private static String fileHash(Path path) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536];int read;
            while((read=in.read(buffer))!=-1)digest.update(buffer,0,read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Pointer readScript(String name, IntByReference size) {
        try {
            String key = name.replace('\\', '/').replaceFirst("^[./]+", "");
            if (!key.contains("/")) key = "script/" + key;
            Memory cached = scriptMemory.get(key);
            if (cached != null) { size.setValue((int) cached.size() - 1); return cached; }
            var entry = scripts.getEntry(key);
            if (entry == null) { errors.add("Missing script: " + key); size.setValue(0); return null; }
            byte[] bytes;
            try (var stream = scripts.getInputStream(entry)) { bytes = stream.readAllBytes(); }
            for(int code:HARPIE_RULE_NAMES)if(key.equals("script/c"+code+".lua")) {
                String table="c"+code;
                String adapter="\n-- Percy permanent-name compatibility adapter; source script remains unchanged.\n"+
                    "local mdpro_original="+table+".initial_effect\n"+
                    table+".initial_effect=function(c)\n"+
                    "local e=Effect.CreateEffect(c)\n"+
                    "e:SetType(EFFECT_TYPE_SINGLE)\n"+
                    "e:SetCode(EFFECT_CHANGE_CODE)\n"+
                    "e:SetProperty(EFFECT_FLAG_CANNOT_DISABLE+EFFECT_FLAG_UNCOPYABLE)\n"+
                    "e:SetValue(76812113)\nc:RegisterEffect(e)\nmdpro_original(c)\nend\n";
                byte[] extra=adapter.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] joined=Arrays.copyOf(bytes,bytes.length+extra.length);System.arraycopy(extra,0,joined,bytes.length,extra.length);bytes=joined;
            }
            cached = new Memory(bytes.length + 1L);
            cached.write(0, bytes, 0, bytes.length);
            cached.setByte(bytes.length, (byte) 0);
            scriptMemory.put(key, cached);
            size.setValue(bytes.length);
            return cached;
        } catch (Exception ex) { errors.add(ex.toString()); size.setValue(0); return null; }
    }

    private int readCard(int code, Pointer target) {
        byte[] bytes = cards.get(code);
        if (bytes == null) { target.clear(76); errors.add("Missing card: " + code); }
        else target.write(0, bytes, 0, bytes.length);
        return 0;
    }

    private int log(Pointer duel, int type) {
        api.get_log_message(duel, logBuffer);
        errors.add("ocgcore[" + type + "]: " + logBuffer.getString(0, "UTF-8"));
        return 0;
    }

    public synchronized Set<Integer> declarableCodes(int[] program) {
        Set<Integer> result=new TreeSet<>();
        cards.forEach((code,data)->{if(CardDeclaration.accepts(data,program))result.add(code);});
        return result;
    }

    public synchronized Session create(int seed, List<Integer> first, List<Integer> second) {
        return create(seed, first, List.of(), second, List.of());
    }

    public synchronized Session create(int seed, List<Integer> first, List<Integer> firstExtra,
                                       List<Integer> second, List<Integer> secondExtra) {
        if (closed) throw new IllegalStateException("Runtime closed");
        for (int code : concat(concat(first, second), concat(firstExtra, secondExtra)))
            if (!cards.containsKey(code)) throw new IllegalArgumentException("Unknown card " + code);
        Pointer duel = api.create_duel(seed);
        if (duel == null) throw new IllegalStateException("create_duel returned null");
        Session session = new Session(duel);
        sessions.add(session);
        try {
            // create_duel loads common Lua; stop before new_card if initialization failed.
            checkErrors();
            for (int player = 0; player < 2; player++) {
                api.set_player_info(duel, player, 8000, 5, 1);
                List<Integer> deck = player == 0 ? first : second;
                // For decks the ABI takes SEQ_DECKTOP/BOTTOM/SHUFFLE, not an array index.
                for (int code : deck) api.new_card(duel, code, (byte) player, (byte) player, (byte) 1, (byte) 0, (byte) 8);
                List<Integer> extra = player == 0 ? firstExtra : secondExtra;
                for (int i = 0; i < extra.size(); i++) api.new_card(duel, extra.get(i), (byte) player, (byte) player, (byte) 64, (byte) i, (byte) 8);
                if (api.query_field_count(duel, (byte) player, (byte) 1) != deck.size()) throw new IllegalStateException("Card insertion count mismatch");
            }
            checkErrors();
            return session;
        } catch (RuntimeException ex) { session.close(); throw ex; }
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> b) {
        List<Integer> all = new ArrayList<>(a); all.addAll(b); return all;
    }

    private void checkErrors() {
        if (!errors.isEmpty()) {
            String message = String.join("\n", errors); errors.clear();
            throw new IllegalStateException(message);
        }
    }

    public record Batch(int status, byte[] bytes) {
        /** PROCESSOR_WAITING also means a non-interactive yield (e.g. an announce result). */
        public boolean waiting() {
            if ((status & 0x10000000) == 0) return false;
            var events = CoreMessages.split(bytes);
            if (events.isEmpty()) return false;
            int kind = Byte.toUnsignedInt(events.get(events.size()-1)[0]);
            return kind == 1 || CoreMessages.isRequest(kind);
        }
        public boolean ended() { return (status & 0x20000000) != 0; }
    }

    public final class Session implements AutoCloseable {
        private Pointer duel;
        private final Memory buffer = new Memory(0x40000);
        private boolean started, waiting;
        private Session(Pointer duel) { this.duel = duel; }
        public void start(boolean deterministic) {
            synchronized (MdproRuntime.this) {
                requireOpen();
                if (started) throw new IllegalStateException("Already started");
                api.start_duel(duel, (3 << 16) | (deterministic ? 0x10 : 0));
                api.query_field_info(duel, buffer);
                if (buffer.getByte(0) != (byte) 162 || buffer.getByte(1) != 3) throw new IllegalStateException("MR3 verification failed");
                started = true;
                checkErrors();
            }
        }
        public byte[] fieldInfo() {
            synchronized (MdproRuntime.this) {
                requireOpen(); int n = api.query_field_info(duel, buffer);
                if (n < 2 || n > buffer.size()) throw new IllegalStateException("Invalid field info");
                return buffer.getByteArray(0, n);
            }
        }
        public byte[] query(int player, int location) {
            return query(player, location, 0x60033B);
        }
        public byte[] query(int player, int location, int flags) {
            synchronized (MdproRuntime.this) {
                requireOpen();
                // Fixed width code, position, type, level, rank, ATK, DEF, and pendulum scales.
                int n = api.query_field_card(duel, (byte)player, (byte)location, flags, buffer, 0);
                if (n < 0 || n > buffer.size()) throw new IllegalStateException("Invalid field query");
                return buffer.getByteArray(0, n);
            }
        }
        public Batch process() {
            synchronized (MdproRuntime.this) {
                requireOpen();
                if (!started || waiting) throw new IllegalStateException("Not ready to process");
                int status = api.process(duel);
                int n = api.get_message(duel, buffer);
                if (n < 0 || n > buffer.size()) throw new IllegalStateException("Invalid native message length " + n);
                checkErrors();
                var batch = new Batch(status, buffer.getByteArray(0, n));
                waiting = batch.waiting();
                return batch;
            }
        }
        public void respond(int response) {
            synchronized (MdproRuntime.this) { requireWaiting(); api.set_responsei(duel, response); waiting = false; }
        }
        public void respond(byte[] response) {
            synchronized (MdproRuntime.this) {
                requireWaiting();
                if (response.length == 0 || response.length > 64) throw new IllegalArgumentException("Response length must be 1..64");
                try (Memory data = new Memory(64)) {
                    data.clear(); data.write(0, response, 0, response.length); api.set_responseb(duel, data);
                }
                waiting = false;
            }
        }
        /** Whether the native core is currently blocked on a response. */
        public boolean waiting() {
            synchronized (MdproRuntime.this) { return duel != null && waiting; }
        }
        private void requireOpen() { if (duel == null) throw new IllegalStateException("Duel closed"); }
        private void requireWaiting() { requireOpen(); if (!waiting) throw new IllegalStateException("No pending request"); }
        @Override public void close() {
            synchronized (MdproRuntime.this) {
                if (duel != null) { api.end_duel(duel); duel = null; buffer.close(); sessions.remove(this); }
            }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        for (Session session : List.copyOf(sessions)) session.close();
        try { scripts.close(); }
        finally {
            scriptMemory.values().forEach(Memory::close);
            scriptMemory.clear(); logBuffer.close(); closed = true;
            synchronized (MdproRuntime.class) { if (owner == this) owner = null; }
        }
    }
}
