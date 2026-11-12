package dev.ide.jvm.fixtures;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Writes what each fixture method returns when run for real, as a properties file: the answers an on-device
 * test compares the VM against, where the fixtures cannot be run natively.
 * Keys are {@code Class#method} and, for the methods taking an int, {@code Class#method(n)}.
 */
public final class Expectations {
    private Expectations() {}

    public static void main(String[] args) throws Exception {
        Properties out = new Properties();
        for (Class<?> fixture : new Class<?>[] { Java21.class, Structured.class, PatternSwitch.class }) {
            for (Method m : fixture.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.getReturnType() != String.class) continue;
                String key = fixture.getSimpleName() + "#" + m.getName();
                if (m.getParameterCount() == 0) {
                    out.setProperty(key, (String) m.invoke(null));
                } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    for (int n = -1; n <= 10; n++) {
                        try {
                            out.setProperty(key + "(" + n + ")", (String) m.invoke(null, n));
                        } catch (ReflectiveOperationException outsideTheMethodsRange) {
                            // an input the method does not take
                        }
                    }
                }
            }
        }
        Path file = Path.of(args[0]);
        Files.createDirectories(file.getParent());
        try (OutputStream os = Files.newOutputStream(file)) {
            out.store(os, null);
        }
    }
}
