package aot;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * SwingUtilities without an event thread: what is posted to it runs where it is posted.
 *
 * The IntelliJ core under the compiler posts to the event thread while it starts (its mock application's
 * invokeLater is SwingUtilities'). The real class starts AWT's toolkit to do that, and an iOS app has none to
 * start. This is the image's counterpart of the java.desktop patch the app's JVM gets (src/desktopPatch).
 */
@TargetClass(className = "javax.swing.SwingUtilities")
final class NoEventThread {
    private NoEventThread() {}

    @Substitute
    public static boolean isEventDispatchThread() { return false; }

    @Substitute
    public static void invokeLater(Runnable doRun) { doRun.run(); }

    @Substitute
    public static void invokeAndWait(Runnable doRun) { doRun.run(); }
}
