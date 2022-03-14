package javax.swing;

import android.os.Looper;

public class SwingUtilities {

    public static boolean isEventDispatchThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
