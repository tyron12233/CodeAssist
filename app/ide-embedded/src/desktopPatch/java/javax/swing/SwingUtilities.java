package javax.swing;

/**
 * The embedded JVM's SwingUtilities: there is no event thread, so what is posted to it runs where it is posted.
 *
 * The IntelliJ core the editor's parsers sit on posts to the event thread while it starts (its mock
 * application's invokeLater is SwingUtilities'). The real class starts AWT's toolkit to do that, and on iOS
 * there is no toolkit to start: the JDK there is built with the Mac one, whose native half is AppKit. This
 * replaces the class in java.desktop (see the app's --patch-module), the way the Android build's shim of the
 * same name stands in where Swing does not exist at all.
 */
public final class SwingUtilities {
    private SwingUtilities() {}
    public static boolean isEventDispatchThread() { return false; }
    public static void invokeLater(Runnable doRun) { doRun.run(); }
    public static void invokeAndWait(Runnable doRun) { doRun.run(); }
}
