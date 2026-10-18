package dev.codex.yugiohmaid;

import java.util.UUID;

public final class MaidPortraitClient {
    private static MaidPortraitPacket current;

    private MaidPortraitClient() {}

    public static void accept(MaidPortraitPacket packet) { current = packet; }
    public static MaidPortraitPacket current() { return current; }
    public static boolean matches(UUID opponentId) { return current != null && current.opponentId().equals(opponentId); }
    public static void clear() { current = null; }
}
