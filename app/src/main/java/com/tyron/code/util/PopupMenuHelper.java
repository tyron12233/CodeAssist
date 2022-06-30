package com.tyron.code.util;

import androidx.appcompat.widget.ForwardingListener;

import java.lang.reflect.Field;

public class PopupMenuHelper {

    private PopupMenuHelper() {

    }

    private static final Field FORWARDING_FIELD;

    static {
        try {
            Class<?> aClass = Class.forName("androidx.appcompat.widget.ForwardingListener");

            FORWARDING_FIELD = aClass.getDeclaredField("mForwarding");
            FORWARDING_FIELD.setAccessible(true);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    /**
     * Helper method to set the forwarding listener of a PopupMenu to be always
     * forwarding touch events
     * @param forwardingListener must be an instance of ForwardingListener
     */
    public static void setForwarding(ForwardingListener forwardingListener) {
        try {
            FORWARDING_FIELD.set(forwardingListener, true);
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
    }
}
