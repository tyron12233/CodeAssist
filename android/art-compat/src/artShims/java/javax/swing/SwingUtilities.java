package javax.swing;

/**
 * ART stub — Android has no Swing. The IntelliJ platform's extension-point listeners touch
 * SwingUtilities (EDT checks / invokeLater). javax.swing is app-definable on ART (unlike java.*), so a
 * headless stub satisfies the references. There is no EDT on Android, so callbacks run inline.
 */
public final class SwingUtilities {
    private SwingUtilities() {}
    public static boolean isEventDispatchThread() { return false; }
    public static void invokeLater(Runnable doRun) { doRun.run(); }
    public static void invokeAndWait(Runnable doRun) { doRun.run(); }
}
