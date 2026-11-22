package aot;

import dev.ide.embedded.EmbeddedEngineHost;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * The engine as functions: a property of the process it runs in, the app's aapt2, a project to open, and one
 * request of the engine's byte protocols answered. See EmbeddedEngineHost, which these enter.
 */
public final class EngineEntry {
    private EngineEntry() {}

    /** aapt2's main as the app has it: arguments with the program name first, what it prints sent to a file. */
    interface Aapt2 extends CFunctionPointer {
        @InvokeCFunctionPointer
        int run(int argc, CCharPointerPointer argv, CCharPointer output);
    }

    /** A system property, for what the engine reads from them: the folders it may write in, the Android toolchain's. */
    @CEntryPoint(name = "aot_engine_property")
    static void property(IsolateThread thread, CCharPointer key, CCharPointer value) {
        System.setProperty(CTypeConversion.toJavaString(key), CTypeConversion.toJavaString(value));
    }

    /** The app's aapt2, as the address it was given at: a pointer is not a value a closure may hold. */
    private static long aapt2Address;

    @CEntryPoint(name = "aot_engine_aapt2")
    static void aapt2(IsolateThread thread, Aapt2 function) {
        aapt2Address = function.rawValue();
        EmbeddedEngineHost.installAapt2(EngineEntry::runAapt2);
    }

    private static Integer runAapt2(String[] args, String output) {
        String[] argv = new String[args.length + 1];
        argv[0] = "aapt2";
        System.arraycopy(args, 0, argv, 1, args.length);
        Aapt2 function = WordFactory.pointer(aapt2Address);
        try (CTypeConversion.CCharPointerPointerHolder held = CTypeConversion.toCStrings(argv);
             CTypeConversion.CCharPointerHolder file = CTypeConversion.toCString(output)) {
            return function.run(argv.length, held.get(), file.get());
        }
    }

    /**
     * The app's store session, asked by what the engine is told it means: 0 whether there is one ("1", or null
     * for none), 1 a current access token, 2 a token to retry with after `arg` (the refused one) was refused.
     * The answer is a string the engine frees, or null.
     */
    interface Session extends CFunctionPointer {
        @InvokeCFunctionPointer
        CCharPointer ask(int question, CCharPointer arg);
    }

    /** The app's session, as the address it was given at (see aapt2Address). */
    private static long sessionAddress;

    @CEntryPoint(name = "aot_engine_session")
    static void session(IsolateThread thread, Session function) {
        sessionAddress = function.rawValue();
        EmbeddedEngineHost.installSession(EngineEntry::askSession);
    }

    private static String askSession(Integer question, String arg) {
        Session function = WordFactory.pointer(sessionAddress);
        CCharPointer answer;
        if (arg == null) {
            answer = function.ask(question, WordFactory.nullPointer());
        } else {
            try (CTypeConversion.CCharPointerHolder held = CTypeConversion.toCString(arg)) {
                answer = function.ask(question, held.get());
            }
        }
        if (answer.isNull()) return null;
        try {
            return CTypeConversion.toJavaString(answer);
        } finally {
            UnmanagedMemory.free(answer);
        }
    }

    /**
     * Starts the whole backend over the folder projects are kept in (see EmbeddedEngineHost.start). Zero, or
     * non-zero with why in `error`.
     */
    @CEntryPoint(name = "aot_engine_start")
    static int start(IsolateThread thread, CCharPointer projectsRoot, CCharPointer error, int capacity) {
        String failure;
        try {
            failure = EmbeddedEngineHost.start(CTypeConversion.toJavaString(projectsRoot));
        } catch (Throwable t) {
            failure = t.toString();
        }
        if (failure.isEmpty()) return 0;
        if (capacity > 0) CTypeConversion.toCString(failure, error, WordFactory.unsigned(capacity));
        return 1;
    }

    /** Opens the project. Zero, or non-zero with why in `error` (at most `capacity` bytes, ended by a zero). */
    @CEntryPoint(name = "aot_engine_open")
    static int open(IsolateThread thread, CCharPointer root, CCharPointer error, int capacity) {
        String failure;
        try {
            failure = EmbeddedEngineHost.open(CTypeConversion.toJavaString(root));
        } catch (Throwable t) {
            failure = t.toString();
        }
        if (failure.isEmpty()) return 0;
        if (capacity > 0) CTypeConversion.toCString(failure, error, WordFactory.unsigned(capacity));
        return 1;
    }

    /**
     * Answers one request. The answer is the caller's to free(); its length goes to `replyLength`, and it is
     * null with a length of zero when there is nothing to say.
     */
    @CEntryPoint(name = "aot_engine_call")
    static CCharPointer call(IsolateThread thread, CCharPointer request, int length, CIntPointer replyLength) {
        byte[] reply;
        try {
            byte[] bytes = new byte[length];
            CTypeConversion.asByteBuffer(request, length).get(bytes);
            reply = EmbeddedEngineHost.call(bytes);
        } catch (Throwable t) {
            reply = new byte[0];
        }
        replyLength.write(reply.length);
        if (reply.length == 0) return WordFactory.nullPointer();
        CCharPointer out = UnmanagedMemory.malloc(reply.length);
        CTypeConversion.asByteBuffer(out, reply.length).put(reply);
        return out;
    }
}
