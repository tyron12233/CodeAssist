package dev.ide.build.engine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * A desktop bootstrap for running a class whose {@code main} is an <i>instance</i> method (no static entry
 * point). The {@code java} launcher can only invoke a static {@code public static void main(String[])}, so
 * to run e.g. {@code class Test { void main() {} }} we fork {@code java ... ReflectiveMainLauncher <class>
 * <args>}: this loads the target, finds its {@code main} (preferring {@code (String[])} then no-arg), and —
 * when the method is not static — constructs the class with its no-arg constructor and invokes it.
 *
 * <p>Pure Java (no Kotlin stdlib), so it can sit on the classpath of a plain-Java program's run without
 * dragging in extra runtime dependencies. Errors/exit propagate like a normal launch: an uncaught exception
 * in the target prints and exits non-zero; {@code System.exit} in the target exits this JVM directly.
 */
public final class ReflectiveMainLauncher {

    private ReflectiveMainLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("ReflectiveMainLauncher: no target class");
            System.exit(1);
            return;
        }
        String target = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);

        Class<?> clazz = Class.forName(target);
        Method main = findMain(clazz);
        if (main == null) {
            System.err.println("ReflectiveMainLauncher: no main(String[]) or main() in " + target);
            System.exit(1);
            return;
        }
        main.setAccessible(true);
        Object receiver = null;
        if (!Modifier.isStatic(main.getModifiers())) {
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            receiver = ctor.newInstance();
        }
        Object[] callArgs = main.getParameterCount() == 0 ? new Object[0] : new Object[] { rest };
        try {
            main.invoke(receiver, callArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            (cause != null ? cause : e).printStackTrace();
            System.exit(1);
        }
    }

    private static Method findMain(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethod("main", String[].class);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }
        try {
            return clazz.getDeclaredMethod("main");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
