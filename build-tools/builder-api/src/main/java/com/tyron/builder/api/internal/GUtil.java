package com.tyron.builder.api.internal;

import com.tyron.builder.api.UncheckedIOException;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.Callable;

public class GUtil {

    public interface RunnableThrowable{
        void run() throws Exception;
    }

    /**
     * Runs the given runnable converting any thrown exception to an unchecked exception via {@link UncheckedException#throwAsUncheckedException(Throwable)}
     *
     * @param runnable The runnable to run
     */
    public static void unchecked(RunnableThrowable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    /**
     * Calls the given callable converting any thrown exception to an unchecked exception via {@link UncheckedException#throwAsUncheckedException(Throwable)}
     *
     * @param callable The callable to call
     * @param <T> Callable's return type
     * @return The value returned by {@link Callable#call()}
     */
    @Nullable
    public static <T> T uncheckedCall(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    public static Properties loadProperties(File propertyFile) {
        try {
            try (FileInputStream inputStream = new FileInputStream(propertyFile)) {
                return loadProperties(inputStream);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Properties loadProperties(URL url) {
        try {
            URLConnection uc = url.openConnection();
            uc.setUseCaches(false);
            return loadProperties(uc.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Properties loadProperties(InputStream inputStream) {
        Properties properties = new Properties();
        try {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {

                }
            }
        }
        return properties;
    }

    public static void saveProperties(Properties properties, File propertyFile) {
        try {
            try (FileOutputStream propertiesFileOutputStream = new FileOutputStream(propertyFile)) {
                properties.store(propertiesFileOutputStream, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void saveProperties(Properties properties, OutputStream outputStream) {
        try {
            try {
                properties.store(outputStream, null);
            } finally {
                outputStream.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean isTrue(@Nullable Object object) {
        if (object == null) {
            return false;
        }
        if (object instanceof Collection) {
            return ((Collection<?>) object).size() > 0;
        } else if (object instanceof String) {
            return ((String) object).length() > 0;
        }
        return true;
    }

    /**
     * Prefer {@link #getOrDefault(Object, Factory)} if the value is expensive to compute or
     * would trigger early configuration.
     */
    @Nullable
    public static <T> T elvis(@Nullable T object, @Nullable T defaultValue) {
        return isTrue(object) ? object : defaultValue;
    }

    @Nullable
    public static <T> T getOrDefault(@Nullable T object, Factory<T> defaultValueSupplier) {
        return isTrue(object) ? object : defaultValueSupplier.create();
    }

}
