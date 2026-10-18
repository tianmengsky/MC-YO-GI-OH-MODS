package dev.codex.yugiohmdpro.engine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Six actual MR3 turns on the production backend, including end-turn discards. */
public final class ReferenceProbe {
    private ReferenceProbe(){}
    public static void verify(Path root) throws Exception {
        try(var runtime=new ReferenceHost(root);
            var session=runtime.create(7,Collections.nCopies(40,91152256),List.of(),Collections.nCopies(40,91152256),List.of())) {
            session.start(false);int turns=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(turns<6) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("Reference probe timed out");
                var batch=session.process();byte[] event=batch.bytes();
                if(event.length>0 && event[0]==1)throw new IllegalStateException("Reference probe response rejected");
                if(event.length>0 && event[0]==40)turns++;
                if(batch.waiting()) {
                    if(session.fieldInfo().length<2 || session.fieldInfo()[1]!=3)throw new IllegalStateException("Reference probe MR3 mismatch");
                    var q=CorePrompt.fromHost(batch.prompt());
                    if(q.kind==16){q.replyTo(session,q.response(new int[0],true,""));continue;}
                    int[] indices=new int[q.multiple?q.min:1];
                    for(int i=0;i<indices.length;i++)indices[i]=i;
                    if(q.kind==10 || q.kind==11) {
                        indices[0]=-1;for(int i=0;i<q.entries.size();i++)if(q.entries.get(i).command() && (q.entries.get(i).value()==7 || q.entries.get(i).value()==3))indices[0]=i;
                        if(indices[0]<0)throw new IllegalStateException("Reference probe missing phase action");
                    }
                    q.replyTo(session,q.response(indices,false,""));
                } else if(event.length==0){if(batch.ended())throw new IllegalStateException("Reference probe ended too soon");Thread.sleep(2);}
            }
        }
    }
}
