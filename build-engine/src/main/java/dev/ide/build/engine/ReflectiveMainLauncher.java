package dev.ide.build.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * A bootstrap that launches a program by resolving its {@code main} entry point <i>reflectively</i>, rather
 * than relying on the VM launcher's fixed {@code public static void main(String[])} lookup. It is forked as
 * {@code <vm> ... ReflectiveMainLauncher <targetClass> <args...>}: it loads {@code targetClass}, finds the
 * best-matching {@code main}, and invokes it — constructing the class with its no-arg constructor first when
 * the method is an instance method.
 *
 * <p>Used by two runners: the desktop {@link JavaExecTask} (forks {@code java}) and the on-device forked
 * {@code dalvikvm} runner (which dexes this class onto the run classpath as an asset). Both need it because a
 * VM's built-in launcher can only start a <b>static</b> {@code main(String[])}, whereas the Kotlin/JVM spec
 * (plus this IDE's instance-{@code main} convenience) admits several other shapes. Verified against Kotlin
 * 2.4.0 bytecode:
 *
 * <ul>
 *   <li>{@code fun main(args: Array<String>)} → static {@code main(String[])}.</li>
 *   <li>{@code fun main()} → static {@code main()} plus a synthetic static {@code main(String[])} bridge.</li>
 *   <li>{@code suspend fun main()} / {@code suspend fun main(args)} → a synthetic static {@code main(String[])}
 *       bridge (wrapping the real {@code main(Continuation)} in {@code runSuspend}).</li>
 *   <li>{@code object O { @JvmStatic fun main(args) }} → static {@code main(String[])} on {@code O}.</li>
 *   <li>{@code object O { @JvmStatic fun main() }} → static {@code main()} on {@code O} <b>with no
 *       {@code (String[])} bridge</b> — the case a VM's native launcher can't start.</li>
 *   <li>{@code class K { companion object { @JvmStatic fun main(args) } }} → static {@code main(String[])}
 *       on the enclosing {@code K}.</li>
 *   <li>a plain {@code class App { fun main() {} }} — an instance {@code main}, no JVM entry-point equivalent;
 *       this IDE runs it as a convenience.</li>
 * </ul>
 *
 * <p>Resolution prefers, in order: static {@code main(String[])} → static {@code main()} → instance
 * {@code main(String[])} → instance {@code main()}, searching the class then its superclasses (so an
 * inherited static {@code main} runs, matching the VM launcher) and picking the most-derived match. Only a
 * {@code void}-returning {@code main} qualifies (the entry-point contract).
 *
 * <p>Pure Java (no Kotlin stdlib), so it can sit on the classpath of a plain-Java program's run without
 * dragging in extra runtime dependencies. Errors/exit propagate like a normal launch: an uncaught exception
 * in the target is printed as {@code Exception in thread "main" …} (mirroring the VM's default handler) and
 * exits non-zero; {@code System.exit} in the target exits this VM directly; a normal return does NOT call
 * {@code System.exit}, so the VM's shutdown still waits for any non-daemon threads the program started.
 */
public final class ReflectiveMainLauncher {

    private ReflectiveMainLauncher() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("ReflectiveMainLauncher: no target class specified.");
            System.exit(1);
            return;
        }
        String target = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        Class<?> clazz;
        try {
            clazz = Class.forName(target);
        } catch (Throwable t) {
            System.err.println("Error: could not find or load main class " + target);
            System.exit(1);
            return;
        }

        Entry entry = resolve(clazz);
        if (entry == null) {
            System.err.println("Error: " + target + " has no runnable main method (expected a static "
                + "main(String[]) / main(), or an instance main on a class with a no-arg constructor).");
            System.exit(1);
            return;
        }

        Object receiver = null;
        if (!entry.isStatic) {
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                receiver = ctor.newInstance();
            } catch (Throwable t) {
                System.err.println("Error: cannot instantiate " + target
                    + " to run its instance main(): " + t);
                System.exit(1);
                return;
            }
        }

        Object[] callArgs = entry.takesArgs ? new Object[] { rest } : new Object[0];
        try {
            entry.method.invoke(receiver, callArgs);
        } catch (InvocationTargetException e) {
            // The program's own throwable — unwrap it so the trace reads like a normal uncaught exception,
            // not a reflection wrapper.
            Throwable cause = e.getTargetException();
            reportUncaught(cause != null ? cause : e);
            System.exit(1);
        } catch (Throwable t) {
            reportUncaught(t);
            System.exit(1);
        }
        // Normal return: no System.exit — let the VM's shutdown wait for any non-daemon threads the program
        // started (matching the java/dalvikvm launcher). System.exit in the target already exited this VM.
    }

    /** Print an uncaught throwable the way the VM's default handler does, so a crashed run looks native. */
    private static void reportUncaught(Throwable cause) {
        System.err.print("Exception in thread \"main\" ");
        cause.printStackTrace(System.err);
        System.err.flush();
    }

    /** The chosen entry point: the {@code main} [method], whether it is [isStatic] (else invoked on a fresh
     *  instance), and whether it [takesArgs] (a {@code String[]} parameter) vs. no parameters. */
    static final class Entry {
        final Method method;
        final boolean isStatic;
        final boolean takesArgs;

        Entry(Method method, boolean isStatic, boolean takesArgs) {
            this.method = method;
            this.isStatic = isStatic;
            this.takesArgs = takesArgs;
        }
    }

    /**
     * Resolve [clazz]'s runnable {@code main}, preferring static over instance and {@code (String[])} over
     * no-arg, searching the class then its superclasses (most-derived match wins per category). Returns null
     * when no {@code void main(String[])} / {@code void main()} exists. Package-private for tests.
     */
    static Entry resolve(Class<?> clazz) {
        Method staticArgs = null, staticNoArg = null, instanceArgs = null, instanceNoArg = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"main".equals(m.getName()) || m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (p.length == 1 && p[0] == String[].class) {
                    if (isStatic) { if (staticArgs == null) staticArgs = m; }
                    else if (instanceArgs == null) instanceArgs = m;
                } else if (p.length == 0) {
                    if (isStatic) { if (staticNoArg == null) staticNoArg = m; }
                    else if (instanceNoArg == null) instanceNoArg = m;
                }
            }
            // Highest-precedence match already found — no superclass can beat a static main(String[]).
            if (staticArgs != null) break;
        }

        Method chosen;
        boolean isStatic, takesArgs;
        if (staticArgs != null) { chosen = staticArgs; isStatic = true; takesArgs = true; }
        else if (staticNoArg != null) { chosen = staticNoArg; isStatic = true; takesArgs = false; }
        else if (instanceArgs != null) { chosen = instanceArgs; isStatic = false; takesArgs = true; }
        else if (instanceNoArg != null) { chosen = instanceNoArg; isStatic = false; takesArgs = false; }
        else return null;
        chosen.setAccessible(true);
        return new Entry(chosen, isStatic, takesArgs);
    }
}
