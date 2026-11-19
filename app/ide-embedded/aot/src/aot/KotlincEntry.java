package aot;

import dev.ide.lang.kotlin.compile.KotlinCompileWorker;
import java.nio.file.Path;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

/** The Kotlin compiler as a function: one compile, from a request file to a result file. */
public final class KotlincEntry {
    private KotlincEntry() {}

    @CEntryPoint(name = "aot_kotlinc")
    static int kotlinc(IsolateThread thread, CCharPointer request, CCharPointer result) {
        return run(CTypeConversion.toJavaString(request), CTypeConversion.toJavaString(result));
    }

    static int run(String request, String result) {
        return KotlinCompileWorker.INSTANCE.run(Path.of(request), Path.of(result));
    }

    /** The same entry from a command line, for tracing on a JVM what the image must keep reachable. */
    public static void main(String[] args) {
        int times = args.length > 2 ? Integer.parseInt(args[2]) : 1, code = 0;
        for (int i = 0; i < times; i++) {
            long started = System.nanoTime();
            code = run(args[0], args[1]);
            System.out.println("compile " + i + ": code " + code + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        }
        System.exit(code);
    }
}
