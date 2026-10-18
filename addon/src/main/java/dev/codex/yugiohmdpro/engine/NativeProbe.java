package dev.codex.yugiohmdpro.engine;

import java.nio.file.*;
import java.util.*;

/** Executed as a separate JVM: exercises the actual frozen DLL, Lua and data callbacks. */
public final class NativeProbe {
    public static String run(Path root) throws Exception {
        List<Integer> codes = List.of(89631139,46986414,70781052,91152256,40640057,30090452,
                28279543,15025844,69247929,12482652,34460851,40453765,47025270,87564352);
        List<Integer> deck = new ArrayList<>();
        for (int code : codes) for (int i = 0; i < 3 && deck.size() < 40; i++) deck.add(code);
        int turns = 0, draws = 0, requests = 0;
        StringBuilder trace = new StringBuilder();
        try (MdproRuntime runtime = new MdproRuntime(root)) {
            // Two concurrent handles share one callback owner without replacing its callbacks.
            try (var other = runtime.create(8, deck, deck); var session = runtime.create(7, deck, deck)) {
                session.start(true);
                other.start(true);
                for (int step = 0; step < 1200 && turns < 6; step++) {
                    var batch = session.process();
                    byte[] data = batch.bytes();
                    trace.append(step).append(' ').append(Integer.toUnsignedString(batch.status(),16))
                            .append(' ').append(HexFormat.of().formatHex(data)).append('\n');
                    if (data.length > 0 && data[0] == 40) turns++;
                    if (data.length > 2 && data[0] == 90) draws += Byte.toUnsignedInt(data[2]);
                    if (data.length > 0 && data[0] == 1) throw new AssertionError("MSG_RETRY");
                    if (batch.waiting()) {
                        requests++;
                        if (data[0] == 11) session.respond(7);
                        else if (data[0] == 16 && data[2] == 0) session.respond(-1);
                        else if (data[0] == 15) {
                            int minimum = Byte.toUnsignedInt(data[3]);
                            byte[] reply = new byte[minimum + 1]; reply[0] = (byte) minimum;
                            for (int i=0; i<minimum; i++) reply[i+1]=(byte)i;
                            session.respond(reply);
                        } else throw new AssertionError("Unexpected fixture prompt " + HexFormat.of().formatHex(data));
                    }
                    if (batch.ended()) break;
                }
            }
            try (var next = runtime.create(9, deck, deck)) { next.start(false); }
        }
        if (turns < 6 || draws < 10) throw new AssertionError("Incomplete game: " + turns + "/" + draws);
        Files.writeString(root.resolve("java-smoke-trace.log"), trace);
        return "PASS: Percy ABI=76 bytes, MR3, turns=" + turns + ", drawn=" + draws + ", requests=" + requests + ", 3 handles closed";
    }
    public static void main(String[] args) throws Exception { System.out.println(run(Path.of(args[0]))); }
}

