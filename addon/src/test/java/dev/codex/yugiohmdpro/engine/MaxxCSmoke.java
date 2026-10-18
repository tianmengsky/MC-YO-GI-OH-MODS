package dev.codex.yugiohmdpro.engine;
import java.nio.file.Path;
/** Focused entry point for the original real-card regression. */
public final class MaxxCSmoke {
    public static void main(String[] args) throws Exception {
        try(var runtime=new ReferenceHost(Path.of(args[0]).resolve("generated/reference-test-runtime"))){
            ReferenceHostSmoke.maxx(runtime,false);ReferenceHostSmoke.maxx(runtime,true);
        }
    }
}
