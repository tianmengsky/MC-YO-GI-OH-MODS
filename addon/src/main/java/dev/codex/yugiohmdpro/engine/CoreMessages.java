package dev.codex.yugiohmdpro.engine;

import java.util.*;

/** Framing for the frozen Percy protocol; one process() batch may contain several events. */
public final class CoreMessages {
    private CoreMessages() {}
    public static boolean isRequest(int kind) {
        return switch(kind) {
            case 10,11,12,13,14,15,16,18,19,20,22,23,24,25,26,132,140,141,142,143 -> true;
            default -> false;
        };
    }
    public static List<byte[]> split(byte[] batch) {
        List<byte[]> result=new ArrayList<>();
        for(int at=0;at<batch.length;) {
            int kind=u(batch,at),length=switch(kind) {
                case 1,37,61,63,65,74,112,113,114 -> 1;
                case 32,35,40,71,72,73,75,76,133 -> 2;
                case 5,41 -> 3;
                case 56,170 -> 5;
                case 91,92,94,100 -> 6;
                case 2,38,165 -> 7;
                case 101,102 -> 8;
                case 54,60,62,64,93,96,97,110,120 -> 9;
                case 53,160 -> 10;
                case 50,55,70 -> 17;
                case 111 -> 27;
                case 30,31,42 -> 3+7*u(batch,at+2);
                case 33,39,90 -> 3+4*u(batch,at+2);
                case 36 -> 3+8*u(batch,at+2);
                case 81 -> 3+4*u(batch,at+2);
                case 83 -> 2+4*u(batch,at+1);
                case 130,131 -> 3+u(batch,at+2);
                case 163,164 -> 3+u(batch,at+1)+(u(batch,at+2)<<8);
                case 161 -> 10+4*(u(batch,at+3)+u(batch,at+5));
                case 162 -> reloadLength(batch,at);
                // Interactive requests are the final event; retain their exact ABI-specific payload.
                // Some frozen/derived cores still emit UPDATE_DATA/UPDATE_CARD (6/7), and custom
                // extensions can use other ids.  They are display notifications, never reasons to
                // abort a duel.  Split a trailing request when one is present; otherwise consume the
                // opaque notification as one event so its payload cannot be mistaken for headers.
                default -> {
                    if(isRequest(kind))yield batch.length-at;
                    int requestAt=findTrailingRequest(batch,at+1);
                    yield requestAt>at?requestAt-at:batch.length-at;
                }
            };
            if(length<1 || at+length>batch.length)throw new IllegalArgumentException("Truncated core message "+kind);
            result.add(Arrays.copyOfRange(batch,at,at+length));at+=length;
        }
        return result;
    }
    private static int reloadLength(byte[] data,int start) {
        int at=start+2;
        for(int p=0;p<2;p++) {
            at+=4;
            for(int i=0;i<7;i++)at+=u(data,at)!=0?3:1;
            for(int i=0;i<8;i++)at+=u(data,at)!=0?2:1;
            at+=6;
        }
        return at-start+1+15*u(data,at);
    }
    private static int u(byte[] bytes,int index) {
        if(index>=bytes.length)throw new IllegalArgumentException("Truncated core message header");
        return Byte.toUnsignedInt(bytes[index]);
    }
    /** Locate the final interactive request after an opaque notification (for example message 6). */
    private static int findTrailingRequest(byte[] batch,int from) {
        for(int at=batch.length-1;at>=from;at--) {
            int kind=Byte.toUnsignedInt(batch[at]);
            if(!isRequest(kind))continue;
            try { CorePrompt.parse(Arrays.copyOfRange(batch,at,batch.length)); return at; }
            catch(RuntimeException ignored) { }
        }
        return -1;
    }
}
