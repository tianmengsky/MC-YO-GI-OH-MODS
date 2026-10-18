package dev.codex.yugiohmdpro.duel;

import com.kiki.yugioh.duel.Duel;

/** Bounded blocking helper used only by synchronous Forge game-test fixtures. */
public final class HostTestAwait {
    private HostTestAwait(){}
    public static void waiting(Duel duel) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while(!duel.isFinished() && DuelBridge.game(duel).prompt==null) {
            DuelBridge.game(duel).pump();
            if(System.nanoTime()>deadline)throw new IllegalStateException("Reference host fixture timed out");
            try{Thread.sleep(2);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        }
    }
}
