package com.tyron.builder.api.internal;

import com.google.common.collect.Collections2;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.util.CollectionUtils;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GUtil {

    private static final Pattern UPPER_LOWER_PATTERN = Pattern.compile("(?m)([A-Z]*)([a-z0-9]*)");

    public static String toString(Collection<?> matches) {
        return matches.toString();
    }

    public static <T extends Enum<T>> T toEnum(Class<? extends T> enumType, Object value) {
        if (enumType.isInstance(value)) {
            return enumType.cast(value);
        }
        if (value instanceof CharSequence) {
            final String literal = value.toString();
            T match = findEnumValue(enumType, literal);
            if (match != null) {
                return match;
            }

            final String alternativeLiteral = toWords(literal, '_');
            match = findEnumValue(enumType, alternativeLiteral);
            if (match != null) {
                return match;
            }

            throw new IllegalArgumentException(
                    String.format("Cannot convert string value '%s' to an enum value of type '%s' (valid case insensitive values: %s)",
                            literal, enumType.getName(), CollectionUtils.join(", ", CollectionUtils.collect(Arrays.asList(enumType.getEnumConstants()), new Transformer<String, T>() {
                                @Override
                                public String transform(T t) {
                                    return t.name();
                                }
                            }))
                    )
            );
        }
        throw new IllegalArgumentException(String.format("Cannot convert value '%s' of type '%s' to enum type '%s'",
                value, value.getClass().getName(), enumType.getName()));
    }

    private static <T extends Enum<T>> T findEnumValue(Class<? extends T> enumType, final String literal) {
        for (T ec : enumType.getEnumConstants()) {
            if (ec.name().equalsIgnoreCase(literal)) {
                return ec;
            }
        }
        return null;
    }

    public static String toWords(CharSequence string, char separator) {
        if (string == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int pos = 0;
        Matcher matcher = UPPER_LOWER_PATTERN.matcher(string);
        while (pos < string.length()) {
            matcher.find(pos);
            if (matcher.end() == pos) {
                // Not looking at a match
                pos++;
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            String group1 = matcher.group(1).toLowerCase();
            String group2 = matcher.group(2);
            if (group2.length() == 0) {
                builder.append(group1);
            } else {
                if (group1.length() > 1) {
                    builder.append(group1.substring(0, group1.length() - 1));
                    builder.append(separator);
                    builder.append(group1.substring(group1.length() - 1));
                } else {
                    builder.append(group1);
                }
                builder.append(group2);
            }
            pos = matcher.end();
        }

        return builder.toString();
    }

    public static String asPath(Iterable<?> collection) {
        return CollectionUtils.join(File.pathSeparator, collection);
    }

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
