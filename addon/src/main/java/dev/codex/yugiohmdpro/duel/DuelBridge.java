package dev.codex.yugiohmdpro.duel;

import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.network.*;
import dev.codex.yugiohmdpro.engine.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Server-thread authority; original Duel remains the identity/lifecycle object for Match. */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon")
public final class DuelBridge {
    private static final Map<Duel,Game> GAMES=new IdentityHashMap<>();
    private static ReferenceHost runtime;
    private static MinecraftServer owner;
    private static int closedCount;
    private static int projectionTicks;
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger("MDProDuel");
    public static boolean active(Duel duel) { return GAMES.containsKey(duel); }
    public static int closedCount() { return closedCount; }
    public static CorePrompt prompt(Duel duel) { Game g=GAMES.get(duel); return g==null?null:g.prompt; }
    public static int turns(Duel duel) { Game g=GAMES.get(duel); return g==null?0:g.turn; }
    public static CompoundTag view(Duel duel,int viewer) { return GAMES.get(duel).snapshot(viewer); }
    static Game game(Duel duel) {return Objects.requireNonNull(GAMES.get(duel));}
    public static CompoundTag projection(Duel duel) { return GAMES.get(duel).board.projection(GAMES.get(duel)); }
    public static boolean canSeeProjection(Duel duel,ServerPlayer player) {return GAMES.get(duel).canSeeProjection(player);}
    public static dev.codex.yugiohfix.duel.DuelPublicInfo publicInfo(Duel duel,int viewer) {
        Game g=GAMES.get(duel);return g.board.publicInfo(g,viewer);
    }
    public static dev.codex.yugiohfix.sound.DuelSoundTracker.Snapshot animationSnapshot(Duel duel) {
        Game g=GAMES.get(duel);return g.board.animationSnapshot(g);
    }
    public static void beforeEnd(Duel duel) {
        Game g=GAMES.get(duel);if(g==null || g.session==null || g.finishing)return;
        g.finishing=true;g.publish();
        try { dev.codex.yugiohfix.duel.ServerPlayerAnimations.stop(duel); }
        catch(Exception ex) { LOG.warn("MDPRO_DISPLAY_END duel="+duel.getDuelId(),ex); }
    }
    public static void start(Duel duel,MinecraftServer server,int starter) {
        if(duel.isFinished() || GAMES.containsKey(duel)) return;
        for(int p=0;p<2;p++) {
            var side=duel.getSide(p);var cards=new ArrayList<com.kiki.yugioh.card.CardDefinition>();
            side.deck.forEach(c->cards.add(c.definition));side.extraDeck.forEach(c->cards.add(c.definition));
            var refusal=dev.codex.yugiohmdpro.rules.ServerBanlist.validate(cards);
            if(refusal!=null){cancel(duel,server,refusal);return;}
        }
        Game game=new Game(duel,server,starter==0 || starter==1 ? starter : new Random().nextInt(2)); GAMES.put(duel,game);
        try {
            List<Integer>[] main=new List[2], extra=new List[2];
            for(int e=0;e<2;e++) {
                DuelSide side=duel.getSide(game.legacy(e));
                main[e]=side.deck.stream().map(c->DuelCatalog.code(c.definition.getId())).toList();
                extra[e]=side.extraDeck.stream().map(c->DuelCatalog.code(c.definition.getId())).toList();
            }
            if(runtime==null) {
                Path root=Path.of(System.getProperty("yugioh.mdpro.referenceRuntime",FMLPaths.GAMEDIR.get().resolve("mdpro-reference").toString()));
                runtime=new ReferenceHost(root); owner=server;
            }
            if(owner!=server) throw new IllegalStateException("内核运行时属于另一个服务端");
            boolean training=dev.codex.yugiohfix.duel.DuelRules.training(duel);
            if(training)for(int p=0;p<2;p++) {
                var arranged=new ArrayList<Integer>(main[p]);
                var rest=new ArrayList<Integer>(arranged.subList(Math.min(5,arranged.size()),arranged.size()));
                Collections.shuffle(rest);var opening=new ArrayList<Integer>(arranged.subList(0,Math.min(5,arranged.size())));
                Collections.reverse(opening);rest.addAll(opening);main[p]=rest;
            }
            else for(int p=0;p<2;p++) {main[p]=new ArrayList<>(main[p]);Collections.shuffle(main[p]);}
            game.session=runtime.create(new Random().nextInt(),main[0],extra[0],main[1],extra[1]);
            // Fix only the opening; later card effects must still be able to shuffle normally.
            int aiSeat=duel.isAiControlled(game.legacy(0))?0:duel.isAiControlled(game.legacy(1))?1:-1;
            game.session.start(false,aiSeat);
            LOG.info("MDPRO_TAKEOVER duel={} starter={} main={}/{} extra={}/{} MR3; legacy start cancelled",duel.getDuelId(),game.first,main[0].size(),main[1].size(),extra[0].size(),extra[1].size());
            game.pump();
        } catch(Exception ex) { game.fail(ex); }
    }
    public static void tick(Duel duel) {
        Game g=GAMES.get(duel); if(g==null || duel.isFinished()) return;
        try {
            if(g.prompt!=null) {
                // A request for a player stops the queue, exactly like MDPro3's messagePass gate:
                // nothing advances until the answer arrives (or the AI/fixture delay expires).
                if(++g.ticks>=DuelPacing.aiDelay()
                        && (duel.isAiControlled(g.legacy(g.prompt.player)) || g.testAuto)) {
                    g.ticks=0; g.auto();
                }
                return;
            }
            if(!g.pacer.ready()) { g.pacer.tick(); return; }
            // 极速档与测试夹具保留"一次排空到下一个请求"的历史行为。
            if(g.instant()) g.pump(); else g.step();
        } catch(Exception ex) { g.fail(ex); }
    }
    public static void autoForTest(Duel duel) {
        Game g=GAMES.get(duel); g.testAuto=true; g.pacer.instant(true);
    }
    /** Published beats so far: the number of times this duel screen was refreshed. */
    public static int beats(Duel duel) { Game g=GAMES.get(duel); return g==null?0:g.pacer.beats(); }
    /** Requests the engine raised for a player, i.e. the refresh count before this feature existed. */
    public static int prompts(Duel duel) { Game g=GAMES.get(duel); return g==null?0:g.promptCount; }
    public static void pacing(int mode) { DuelPacing.mode(mode); }
    public static String pacing() { return DuelPacing.name(DuelPacing.mode()); }
    public static void sync(Duel duel) {
        Game g=GAMES.get(duel);if(g==null)return;
        for(int viewer=0;viewer<2;viewer++) {var p=duel.getSide(viewer).player(g.server);if(p!=null)open(duel,p);}
    }
    public static void open(Duel duel,ServerPlayer player) {
        Game g=GAMES.get(duel);if(g==null)return;
        int viewer=duel.sideOf(player.getUUID());if(viewer<0)return;
        CompoundTag tag=g.snapshot(viewer);tag.putBoolean("open",true);DuelNetwork.send(player,tag);
    }
    public static void end(Duel duel,String reason) {
        Game g=GAMES.remove(duel); if(g==null) return;
        try {
            g.clearProjection();
            for(int i=0;i<2;i++) {
                ServerPlayer player=duel.getSide(i).player(g.server); if(player==null) continue;
                CompoundTag tag=new CompoundTag(); tag.putUUID("duel",duel.getDuelId()); tag.putBoolean("finished",true); tag.putString("message",reason);
                DuelNetwork.send(player,tag);
            }
        } finally {
            if(g.session!=null) { g.session.close(); closedCount++; }
            LOG.info("MDPRO_CLOSE duel={} winner={} reason={}",duel.getDuelId(),duel.winner(),reason);
        }
    }
    public static void legacyAction(ServerPlayer player,C2SDuelActionPacket packet) {
        if(player==null) return;
        Duel duel=DuelManager.get(player.getServer()).duelOf(player.getUUID());
        if(duel==null || duel.isFinished()) return;
        if(packet.type()==DuelActionType.SURRENDER) duel.surrender(duel.sideOf(player.getUUID()));
        else { open(duel,player); }
    }
    public static void input(ServerPlayer player,UUID id,int revision,int[] indices,boolean cancel,String text,boolean surrender) {
        if(player==null) return;
        Duel duel=DuelManager.get(player.getServer()).duelOf(player.getUUID());
        if(duel==null || !duel.getDuelId().equals(id) || duel.isFinished()) return;
        Game g=GAMES.get(duel); if(g==null) return;
        if(surrender) { duel.surrender(duel.sideOf(player.getUUID())); return; }
        if(revision!=g.revision || g.prompt==null || g.legacy(g.prompt.player)!=duel.sideOf(player.getUUID())) return;
        g.answer(duel.sideOf(player.getUUID()),revision,indices,cancel,text);
    }
    /** Cancellation is not a rules draw and must not award a win, loss or side-deck round. */
    public static void cancel(Duel duel,MinecraftServer server,Component reason) {
        if(duel.isFinished())return;
        var match=DuelManager.get(server).matchOf(duel.getSide(0).playerId);
        if(match!=null)((dev.codex.yugiohmdpro.mixin.MatchAccess)(Object)match).mdpro$setOver(true);
        var lifecycle=(dev.codex.yugiohmdpro.mixin.DuelLifecycleAccess)(Object)duel;
        lifecycle.mdpro$finished(true);lifecycle.mdpro$winner(-2);
        try { dev.codex.yugiohfix.duel.ServerPlayerAnimations.stop(duel); }
        catch(Exception ex) { LOG.warn("MDPRO_CANCEL_ANIMATIONS",ex); }
        for(int side=0;side<2;side++) {
            var player=duel.getSide(side).player(server);if(player==null)continue;
            try {
                var tag=new CompoundTag();tag.putUUID("duel",duel.getDuelId());tag.putBoolean("finished",true);
                tag.putBoolean("cancelled",true);tag.putString("message",reason.getString());
                DuelNetwork.send(player,tag);player.sendSystemMessage(reason);
            } catch(Exception ex) { LOG.warn("MDPRO_CANCEL_NOTIFICATION",ex); }
        }
        end(duel,reason.getString());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        for(Game g:List.copyOf(GAMES.values())) if(g.server==event.getServer()) cancel(g.duel,g.server,Component.literal("服务器关闭，对局取消，不计胜负"));
        if(owner==event.getServer() && runtime!=null) {
            try { runtime.close(); } catch(Exception ex) { LOG.error("MDPro close",ex); }
            runtime=null; owner=null;
        }
        projectionTicks=0;
    }
    @SubscribeEvent public static void projectionTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END || ++projectionTicks%20!=0)return;
        // Refresh idle duels too, so players arriving midway receive the current board.
        for(Game g:List.copyOf(GAMES.values()))if(g.server==event.getServer()) {
            try{if(g.displayWarning)g.publish();else g.publishProjection();}catch(Exception ex){g.displayFailure(ex);}
        }
    }
    public static final class Game {
        final Duel duel; final MinecraftServer server; final int first;
        final NativeBoard board;
        final double anchorX,anchorY,anchorZ,anchorYaw;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final Set<UUID> projectionViewers=new HashSet<>();
        ReferenceHost.Session session; CorePrompt prompt,lastPrompt;
        int revision,ticks,turn,turnPlayer,phase=1,botActions,retries;
        String notice=""; boolean testAuto,finishing,displayWarning,retryPending;
        int nativeStatus; String lastBatch="";
        /** Presentation beat state: one publish per visible action, then a hold of that action's length. */
        final DuelPacer pacer=new DuelPacer(DuelPacing.instant());
        /** Requests the engine raised for a player: the refresh count this duel had before pacing. */
        int promptCount;
        final boolean[] normalUsed=new boolean[2];
        final Deque<String> history=new ArrayDeque<>();
        /** Codes of the currently open chain, in link order. Display only; cleared when the chain ends. */
        final List<Integer> chainLinks=new ArrayList<>();
        Game(Duel d,MinecraftServer s,int f) {
            duel=d;server=s;first=f;board=new NativeBoard(d);
            var anchor=dev.codex.yugiohfix.duel.DuelAnchors.capture(d,d.getSide(0).player(s),d.getSide(1).player(s));
            anchorX=anchor.x();anchorY=anchor.y();anchorZ=anchor.z();anchorYaw=anchor.yaw();
            var player=d.getSide(0).player(s);if(player==null)player=d.getSide(1).player(s);
            dimension=player==null?s.overworld().dimension():player.level().dimension();
        }
        int legacy(int engine) { return engine^first; }
        void record(String line) { history.addLast(line); while(history.size()>16) history.removeFirst(); }
        boolean instant() { return testAuto || pacer.instant(); }
        /** Everything the duel screen renders, plus the phase and turn it renders under. */
        long fingerprint() { return session.stateFingerprint()*31+turn*7+phase; }
        static int kindOf(byte[] data) { return data.length==0?0:Byte.toUnsignedInt(data[0]); }
        /** Set when a core message ended the duel; both pump loops stop on it. */
        private static final int ENDED=-1;

        /** Instant mode: drain the host queue up to the next request (the historical behaviour). */
        void pump() {
            if(session.waiting())return;
            for(int step=0;step<128 && !duel.isFinished();step++) {
                var batch=session.process();
                nativeStatus=batch.status();lastBatch=HexFormat.of().formatHex(batch.bytes());
                if(batch.bytes().length==0) {
                    if(batch.ended())throw new IllegalStateException("示例宿主结束但没有胜负消息");
                    return; // Host runs off-thread; never block the Minecraft server waiting for output.
                }
                int outcome=observe(batch.bytes());
                if(outcome==ENDED)return;
                if(batch.waiting() && settle(batch))return;
                if(batch.ended()) throw new IllegalStateException("内核结束但没有胜负消息");
            }
        }

        /**
         * Paced mode: consume events until one visible action has been shown, publish it, then hold
         * for that action's animation length. Messages that cannot change the screen, and visible
         * messages the core had already folded into one batch, cost no beat at all.
         */
        void step() {
            if(session.waiting())return;
            for(int budget=0;budget<64 && !duel.isFinished();budget++) {
                var batch=session.process();
                nativeStatus=batch.status();lastBatch=HexFormat.of().formatHex(batch.bytes());
                if(batch.bytes().length==0) {
                    if(batch.ended())throw new IllegalStateException("示例宿主结束但没有胜负消息");
                    pacer.idle();
                    return;
                }
                int kind=kindOf(batch.bytes());
                int outcome=observe(batch.bytes());
                if(outcome==ENDED)return;
                if(batch.waiting() && settle(batch))return;
                if(DuelPacing.visible(kind) && pacer.beat(kind,fingerprint())) {
                    revision++; publish();
                    return;
                }
            }
            pacer.idle();
        }

        /** Applies one core message to the display state. Returns its kind, or {@link #ENDED}. */
        int observe(byte[] data) {
            int kind=kindOf(data);
            board.observe(this,data);
            if(kind==1) {
                notice="选择不符合内核要求，请重新选择。"; retries++; retryPending=true;ticks=0;
            }
            if(kind==40) {
                turn++; turnPlayer=Byte.toUnsignedInt(data[1]); normalUsed[turnPlayer]=false;botActions=0;
                chainLinks.clear(); record("—— 回合 "+turn+"："+duel.getSide(legacy(turnPlayer)).playerName+" ——");
            }
            if(kind==60 && data.length>=9)normalUsed[Byte.toUnsignedInt(data[5])]=true;
            if(kind==54 && data.length>=9 && (data[6]&255)==4)normalUsed[Byte.toUnsignedInt(data[5])]=true;
            if(kind==41) { phase=Short.toUnsignedInt(ByteBuffer.wrap(data,1,2).order(ByteOrder.LITTLE_ENDIAN).getShort()); }
            if(kind==70 && data.length>=5) { int code=ByteBuffer.wrap(data,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0x7fffffff;
                record("发动："+DuelCatalog.name(code)); if(chainLinks.size()<32)chainLinks.add(code); }
            if(kind==74) { record("连锁处理完毕"); chainLinks.clear(); }
            for(String line:DuelLog.describe(this,kind,data)) record(line);
            if(kind==5) { int win=Byte.toUnsignedInt(data[1]);
                if(win>2)throw new IllegalStateException("Invalid native winner: "+win);
                duel.endDuel(win<2?legacy(win):-1,Component.literal("MDPro 内核判定决斗结束")); return ENDED; }
            return kind;
        }

        /**
         * Handles a request. Returns true when the caller must stop (the request is published and
         * waits for a player), false when the engine must be re-read because the request was settled
         * without an interface. A reply sent from inside a batch makes that batch stale, so the
         * remaining events are skipped; reading on would leave a prompt the engine is no longer
         * waiting for, and answering that aborts the duel with "No pending request".
         */
        boolean settle(ReferenceHost.Batch batch) {
            CorePrompt parsed=CorePrompt.fromHost(batch.prompt());
            parsed.prepareDeclaration(runtime);
            // An empty chain question needs no interface, and a request with exactly one legal
            // answer is answered here as MDPro3 does, so the client never sees a pointless dialog.
            boolean decline=parsed.kind==16 && parsed.entries.isEmpty() && parsed.cancelable;
            int[] forced=decline?null:autoChoice(parsed);
            if(decline || forced!=null) {
                lastPrompt=parsed;
                if(decline) session.respond(-1);
                else {
                    byte[] answer=parsed.response(forced,false,"");
                    record("自动应答："+parsed.title);
                    notice="";
                    reply(parsed,answer);
                }
                return false;
            }
            prompt=parsed;if(!retryPending)retries=0;retryPending=false;revision++; ticks=0;
            pacer.prompt(); promptCount++; publish();
            return true;
        }

        void respond(byte[] bytes) {
            CorePrompt q=prompt;
            if(q==null)return;
            // A duplicate client packet or a stale GUI snapshot must never turn into a draw.
            // If the core already consumed this request, discard the stale answer and rebuild
            // the next prompt from the native message stream.
            if(!session.waiting()) {
                LOG.warn("Ignoring stale MDPro answer duel={} revision={}",duel.getDuelId(),revision);
                prompt=null; notice=""; resume(); return;
            }
            reply(q,bytes); lastPrompt=q; prompt=null; notice=""; resume();
        }
        /** Answers leave immediately; only the presentation that follows is paced. */
        void resume() { pacer.prompt(); if(instant()) pump(); }
        /** Integer-style replies must not be confused with list replies; the core keeps them separate. */
        void reply(CorePrompt q,byte[] bytes) {
            q.replyTo(session,bytes);
        }
        void answer(int viewer,int expectedRevision,int[] indices,boolean cancel,String text) {
            if(duel.isFinished() || expectedRevision!=revision || prompt==null || legacy(prompt.player)!=viewer)return;
            if(text.startsWith("search:")) { search(text.substring(7));return; }
            byte[] encoded;
            try { encoded=prompt.kind==22 && text.equals("counters")?prompt.counterResponse(indices):prompt.response(indices,cancel,text); }
            catch(IllegalArgumentException ex) {
                notice=ex.getMessage();revision++;publish();return;
            }
            try { respond(encoded); } catch(Exception ex) { fail(ex); }
        }
        /** The single legal answer of an otherwise pointless prompt, or null when a real choice exists. */
        static int[] autoChoice(CorePrompt q) {
            if(q.kind==14 && q.entries.size()==1) return new int[]{0};
            if(q.kind==19 && q.entries.size()==1) return new int[]{0};
            // Only when declining is impossible: a cancelable single candidate must stay the player's call.
            if(q.kind==15 && !q.cancelable && q.min==1 && q.entries.size()==1) return new int[]{0};
            return null;
        }
        void fail(Exception ex) {
            LOG.error("MDPRO_FAILURE duel="+duel.getDuelId()+" turn="+turn+" revision="+revision+
                    " prompt="+(prompt==null?-1:prompt.kind)+" status="+Integer.toHexString(nativeStatus)+" batch="+lastBatch,ex);
            cancel(duel,server,Component.literal("MDPro 对局因错误取消，不计胜负："+ex.getMessage()));
        }
        void search(String text) {
            if(prompt==null || prompt.kind!=142) return;
            prompt.entries.clear();
            for(int code:prompt.allowed.isEmpty()?List.<Integer>of():DuelCatalog.search(text,prompt.allowed))
                prompt.entries.add(new CorePrompt.Entry(DuelCatalog.name(code)+" · "+code,code,code,0));
            if(prompt.entries.isEmpty()) notice="没有符合的卡名，请换一个关键字。";
            else notice="";
            revision++; publish();
        }
        void auto() {
            if(retries>16)throw new IllegalStateException("内核连续拒绝自动应答："+prompt.kind);
            CorePrompt q=prompt; int k=q.kind;
            if(k==16) { respond(CorePrompt.integer(q.cancelable?-1:0)); return; }
            if(k==12 || k==13) { respond(CorePrompt.integer(1)); return; }
            if(k==10 || k==11) {
                int index=-1;
                if(botActions++<12) {
                    for(int i=0;i<q.entries.size();i++) {
                        int type=q.entries.get(i).value()&0xffff;
                        if(k==11 && (type==0 || type==1 || type==5) || k==10 && type==1) { index=i;break; }
                    }
                }
                if(index<0) for(int i=0;i<q.entries.size();i++) { int type=q.entries.get(i).value()&0xffff;
                    if(k==11 && (type==6 || type==7) || k==10 && (type==2 || type==3)) {index=i;break;} }
                if(index>=0) respond(q.response(new int[]{index},false,"")); else throw new IllegalStateException("Bot has no legal action");
                return;
            }
            if(k==26) { respond(q.finishable || q.cancelable ? CorePrompt.integer(-1) : new byte[]{1,0});return; }
            if(k==142) {
                int code=q.allowed.stream().filter(DuelCatalog::allowed).min(Integer::compare).orElseThrow(()->new IllegalStateException("没有合法的宣言卡名"));
                q.entries.clear();q.entries.add(new CorePrompt.Entry(DuelCatalog.name(code),code,code,0));
                respond(q.response(new int[]{0},false,Integer.toString(code)));return;
            }
            if(k==23) {
                int[] chosen=findSum(q); if(chosen==null) throw new IllegalStateException("Bot cannot select sum");
                respond(q.response(chosen,false,""));return;
            }
            if(k==22) {
                List<Integer> chosen=new ArrayList<>();
                for(int i=0;i<q.entries.size() && chosen.size()<q.target;i++)
                    for(int n=0;n<q.entries.get(i).weight() && chosen.size()<q.target;n++) chosen.add(i);
                respond(q.response(chosen.stream().mapToInt(i->i).toArray(),false,""));return;
            }
            int count=q.multiple?q.min:1;
            if(k==20) {
                var indices=new ArrayList<Integer>();for(int i=0;i<q.entries.size();i++)indices.add(i);
                indices.sort(Comparator.comparingInt((Integer i)->q.entries.get(i).weight()).reversed());
                var chosen=new ArrayList<Integer>();int weight=0;
                for(int i:indices) {if(weight>=q.min)break;if(chosen.size()>=q.max)break;chosen.add(i);weight+=q.entries.get(i).weight();}
                respond(q.response(chosen.stream().mapToInt(i->i).toArray(),false,""));return;
            }
            int[] selected=new int[count];for(int i=0;i<count;i++)selected[i]=i;
            respond(q.response(selected,false,""));
        }
        static int[] findSum(CorePrompt q) {
            int[] weights=q.entries.stream().mapToInt(CorePrompt.Entry::weight).toArray();
            return MaterialSelection.select(weights,q.mandatory,q.min,q.max,q.target,q.mode);
        }
        void publish() {
            if(session==null || duel.isFinished()) return;
            try { publishDisplay(); } catch(Exception ex) { displayFailure(ex); }
        }
        void displayFailure(Exception ex) {
            if(!displayWarning) { displayWarning=true;LOG.warn("MDPRO_DISPLAY_RETRY duel="+duel.getDuelId(),ex); }
        }
        private void publishDisplay() {
            for(int viewer=0;viewer<2;viewer++) {
                ServerPlayer player=duel.getSide(viewer).player(server); if(player!=null) {
                    DuelNetwork.send(player,snapshot(viewer));
                    dev.codex.yugiohfix.network.FixNetwork.publicInfo(player,board.publicInfo(this,viewer));
                }
            }
            if(!finishing)dev.codex.yugiohfix.duel.ServerPlayerAnimations.sync(duel);
            publishProjection();
            displayWarning=false;
        }
        boolean canSeeProjection(ServerPlayer player) {
            return player.level().dimension().equals(dimension)
                    && player.distanceToSqr(anchorX,anchorY,anchorZ)<=32.0*32.0;
        }
        CompoundTag removeProjection() {
            var tag=new CompoundTag();tag.putUUID("duel",duel.getDuelId());tag.putBoolean("finished",true);return tag;
        }
        void clearProjection() {
            var tag=removeProjection();
            for(UUID id:projectionViewers) {
                var player=server.getPlayerList().getPlayer(id);if(player!=null)DuelNetwork.sendProjection(player,tag);
            }
            projectionViewers.clear();
        }
        void publishProjection() {
            if(session==null)return;
            if(finishing || duel.isFinished()){clearProjection();return;}
            var recipients=server.getPlayerList().getPlayers().stream().filter(this::canSeeProjection).toList();
            var current=new HashSet<UUID>();
            // Query the native board once per update, independent of the number of spectators.
            CompoundTag tag=recipients.isEmpty()?null:board.projection(this);
            for(var player:recipients){current.add(player.getUUID());DuelNetwork.sendProjection(player,tag);}
            for(UUID id:projectionViewers)if(!current.contains(id)) {
                var player=server.getPlayerList().getPlayer(id);if(player!=null)DuelNetwork.sendProjection(player,removeProjection());
            }
            projectionViewers.clear();projectionViewers.addAll(current);
        }
        CompoundTag snapshot(int viewer) {
            CompoundTag tag=new CompoundTag(); tag.putUUID("duel",duel.getDuelId());tag.putInt("revision",revision);
            tag.putString("title","MDPro · 大师规则 3 · 回合 "+turn+" · "+phaseName(phase));
            tag.putString("notice",notice); tag.putString("history",String.join("\n",history));
            tag.putBoolean("surrenderLocked",dev.codex.yugiohfix.duel.DuelRules.cancelSurrender(duel));
            ListTag rows=new ListTag(); int[] lp=lifePoints();
            for(int relative=1;relative>=0;relative--) {
                int side=viewer^relative, engine=legacy(side);
                row(rows,duel.getSide(side).playerName+" · LP "+lp[engine],0);
                duel.getSide(side).lifePoints=lp[engine];
                for(int loc:new int[]{1,64,4,8,16,32,2}) {
                    byte[] bytes=session.query(engine,loc); ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    int count=0;
                    while(b.remaining()>=4) {
                        int start=b.position(), size=b.getInt(); if(size<4 || start+size>bytes.length) throw new IllegalStateException("Invalid card query length");
                        if(size>8) {
                            b.getInt(); int code=b.getInt(),position=b.getInt(),type=b.getInt(),level=b.getInt(),rank=b.getInt(),atk=b.getInt(),def=b.getInt(),ls=b.getInt(),rs=b.getInt();
                            int sequence=(position>>>16)&255,pos=position>>>24;
                            boolean hidden=loc==1 || relative==1 && (loc==2 && (pos&5)==0 || loc==64 && (pos&5)==0 || (loc==4 || loc==8) && (pos&5)==0) || loc==32 && (pos&5)==0;
                            if(!hidden) row(rows,DuelCatalog.location(side,loc,sequence)+" · "+DuelCatalog.name(code)
                                    +(loc==4?" ["+positionName(pos)+"] ATK "+atk+" / DEF "+def+" · "+(rank>0?"阶 "+rank:"星 "+level):"")
                                    +(ls>0 || rs>0?" · 刻度 "+ls+"/"+rs:""),code);
                            else if(loc==4 || loc==8 || loc==32) row(rows,DuelCatalog.location(side,loc,sequence)+" · 里侧卡片",0);
                            count++;
                        }
                        b.position(start+size);
                    }
                    if(loc==1 || loc==64 || loc==2) row(rows,(relative==0?"自己":"对方")+" "+(loc==1?"卡组":loc==64?"额外卡组":"手牌")+"："+count+" 张",0);
                }
            }
            tag.put("rows",rows); boolean can=prompt!=null && legacy(prompt.player)==viewer;
            tag.putBoolean("canAct",can);tag.putString("prompt",can?prompt.title:"等待对方操作…");
            tag.putInt("phase",phase);tag.putInt("turn",turn);tag.putInt("turnPlayer",legacy(turnPlayer));
            ListTag chain=new ListTag();
            for(int i=0;i<chainLinks.size();i++) { CompoundTag link=new CompoundTag();link.putInt("index",i+1);link.putInt("code",chainLinks.get(i));chain.add(link); }
            tag.put("chain",chain);
            ListTag choices=new ListTag();
            if(can) {
                tag.putBoolean("multiple",prompt.multiple);tag.putInt("kind",prompt.kind);tag.putInt("mandatory",prompt.mandatory);
                tag.putInt("min",prompt.min);tag.putInt("max",prompt.max);tag.putInt("target",prompt.target);tag.putInt("mode",prompt.mode);
                tag.putBoolean("cancelable",prompt.cancelable);tag.putBoolean("finishable",prompt.finishable);
                tag.putString("requirement",prompt.multiple?"选择 "+prompt.min+"～"+prompt.max+" 项；已自动计入必选素材 "+prompt.mandatory:"点击一个行动");
                boolean idle=prompt.kind==10 || prompt.kind==11;
                for(var e:prompt.entries) {
                    CompoundTag choice=new CompoundTag();choice.putString("label",e.label());choice.putInt("code",e.code());
                    // The raw engine answer, so the client can tell yes from no without parsing labels.
                    choice.putInt("value",e.value());
                    choice.putInt("player",e.player()<0?-1:legacy(e.player()));choice.putInt("location",e.location());choice.putInt("sequence",e.sequence());
                    // weight carries the material level (sum requests) or the counter capacity.
                    choice.putInt("weight",e.weight());
                    // Idle commands split into one verb group per card; engine commands keep their raw value.
                    choice.putInt("action",idle?(e.command()?e.value():(e.value()&0xffff)):-1);
                    choice.putBoolean("command",e.command());
                    choice.putString("note",e.note());choice.putString("detail",e.detail());
                    choices.add(choice);
                }
            }
            tag.put("choices",choices);board.write(this,viewer,lp,tag);return tag;
        }
        int[] lifePoints() {
            if(session.fieldInfo().length==0)return new int[]{8000,8000};
            ByteBuffer b=ByteBuffer.wrap(session.fieldInfo()).order(ByteOrder.LITTLE_ENDIAN); b.position(2);int[] result=new int[2];
            for(int p=0;p<2;p++) {result[p]=b.getInt();for(int i=0;i<7;i++)if(b.get()!=0){b.get();b.get();}for(int i=0;i<8;i++)if(b.get()!=0)b.get();b.position(b.position()+6);}
            return result;
        }
        static void row(ListTag rows,String text,int code) {CompoundTag r=new CompoundTag();r.putString("label",text);r.putInt("code",code);rows.add(r);}
        static String phaseName(int p) {return switch(p){case 1->"抽卡阶段";case 2->"准备阶段";case 4->"主要阶段 1";case 8,16,32,64,128->"战斗阶段";case 256->"主要阶段 2";case 512->"结束阶段";default->"阶段 "+p;};}
        static String positionName(int p) {return switch(p){case 1->"攻击";case 4->"守备";case 8->"里侧守备";default->"里侧";};}
    }
}
