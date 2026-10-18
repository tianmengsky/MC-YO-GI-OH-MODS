package dev.codex.yugiohmdpro.engine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Full production host duel, including combat, LP updates and an actual core win. */
public final class TermuxHostSmoke {
    private static List<Integer>[] deck(Path path) throws Exception {
        List<Integer> main=new ArrayList<>(),extra=new ArrayList<>();
        List<Integer> current=main;
        for(String line:java.nio.file.Files.readAllLines(path)) {
            line=line.strip();
            if(line.equals("#extra")){current=extra;continue;}
            if(line.startsWith("!"))break;
            if(!line.isEmpty() && Character.isDigit(line.charAt(0)))current.add(Integer.parseInt(line));
        }
        return new List[]{main,extra};
    }
    public static void main(String[] args) throws Exception {
        List<Integer>[] first=args.length>1?deck(Path.of(args[1])):new List[]{Collections.nCopies(40,91152256),List.of()};
        List<Integer>[] second=args.length>2?deck(Path.of(args[2])):first;
        int aiSeat=args.length>3?Integer.parseInt(args[3]):1;
        int seed=args.length>4?Integer.parseInt(args[4]):21;
        try(var runtime=new ReferenceHost(Path.of(args[0]).resolve("generated/full-duel-runtime"));
            var session=runtime.create(seed,first[0],first[1],second[0],second[1])) {
            session.start(false,aiSeat);
            int turns=0,prompts=0,summons=0,attacks=0,damage=0,winner=-1;
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
            while(System.nanoTime()<deadline) {
                var batch=session.process();
                byte[] bytes=batch.bytes();
                if(bytes.length>0) {
                    int kind=bytes[0]&255;
                    if(kind==1)throw new AssertionError("Native response rejected");
                    if(kind==40)turns++;
                    if(kind==60)summons++;
                    if(kind==110)attacks++;
                    if(kind==91)damage++;
                    if(kind==5){winner=bytes[1]&255;break;}
                }
                if(batch.waiting()) {
                    if(session.fieldInfo().length<2 || session.fieldInfo()[1]!=3)throw new AssertionError("Not MR3");
                    var prompt=CorePrompt.fromHost(batch.prompt());
                    prompt.prepareDeclaration(runtime);
                    if(prompt.player==aiSeat)throw new AssertionError("Windbot failed to answer its own request");
                    PacingSmoke.answer(session,prompt);prompts++;
                } else if(bytes.length==0) {
                    if(batch.ended())break;
                    Thread.sleep(2);
                }
            }
            if(winner<0 || winner>1 || turns<2 || summons<2 || attacks<1 || damage<1)
                throw new AssertionError("Incomplete duel: winner="+winner+" turns="+turns+" summons="+summons+" attacks="+attacks+" damage="+damage);
            System.out.println("PASS complete MR3 duel vs Windbot: winner="+winner+" turns="+turns+" prompts="+prompts+" summons="+summons+" attacks="+attacks+" damage="+damage+" aiSeat="+aiSeat+" seed="+seed);
        }
    }
}
