package org.jetbrains.kotlin.com.intellij.util.containers;

import android.annotation.SuppressLint;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;

import sun.misc.Unsafe;

public class UnsafeUtil {


    public static Unsafe findUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException se) {
            return AccessController.doPrivileged((PrivilegedAction<Unsafe>) () -> {
                try {
                    Class<Unsafe> type = Unsafe.class;
                    try {
                        @SuppressLint("DiscouragedPrivateApi") Field field = type.getDeclaredField("theUnsafe");
                        field.setAccessible(true);
                        return type.cast(field.get(type));
                    } catch (Exception e) {
                        for (Field field : type.getDeclaredFields()) {
                            if (type.isAssignableFrom(field.getType())) {
                                field.setAccessible(true);
                                return type.cast(field.get(type));
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Unsafe unavailable", e);
                }
                throw new RuntimeException("Unsafe unavailable");
            });
        }
    }

}
