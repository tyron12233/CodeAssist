package aot;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.Origin;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

/** D8 as a function: its command-line arguments one per line in a file, what it reports written to another. */
public final class D8Entry {
    private D8Entry() {}

    @CEntryPoint(name = "aot_d8")
    static int d8(IsolateThread thread, CCharPointer argFile, CCharPointer logFile) {
        return run(CTypeConversion.toJavaString(argFile), CTypeConversion.toJavaString(logFile));
    }

    static int run(String argFile, String logFile) {
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(Path.of(logFile), StandardCharsets.UTF_8))) {
            try {
                List<String> args = Files.readAllLines(Path.of(argFile), StandardCharsets.UTF_8);
                DiagnosticsHandler handler = new DiagnosticsHandler() {
                    @Override public synchronized void error(Diagnostic d) { log.println("error: " + text(d)); }
                    @Override public synchronized void warning(Diagnostic d) { log.println("warning: " + text(d)); }
                    @Override public synchronized void info(Diagnostic d) { log.println("info: " + text(d)); }
                };
                D8.run(D8Command.parse(args.toArray(new String[0]), Origin.root(), handler).build());
                return 0;
            } catch (Throwable t) {
                log.println("D8 dexing failed: " + t);
                t.printStackTrace(log);
                return 1;
            }
        } catch (Throwable t) {
            return 2;
        }
    }

    private static String text(Diagnostic d) {
        String where = d.getOrigin() == Origin.unknown() ? "" : d.getOrigin() + ": ";
        return where + d.getDiagnosticMessage();
    }

    /** The same entry from a command line, for tracing on a JVM what the image must keep reachable. */
    public static void main(String[] args) {
        System.exit(run(args[0], args[1]));
    }
}
