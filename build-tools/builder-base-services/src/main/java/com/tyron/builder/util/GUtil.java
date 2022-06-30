package com.tyron.builder.util;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.util.internal.CollectionUtils;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
                            literal, enumType.getName(), CollectionUtils.join(", ", CollectionUtils.collect(
                                    asList(enumType.getEnumConstants()), new Transformer<String, T>() {
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

    public static void addToMap(Map<String, String> dest, Map<?, ?> src) {
        for (Map.Entry<?, ?> entry : src.entrySet()) {
            dest.put(entry.getKey().toString(), entry.getValue().toString());
        }
    }

    public static boolean isSecureUrl(URI url) {
        /*
         * TL;DR: http://127.0.0.1 will bypass this validation, http://localhost will fail this validation.
         *
         * Hundreds of Gradle's integration tests use a local web-server to test logic that relies upon
         * this behavior.
         *
         * Changing all of those tests so that they use a KeyStore would have been impractical.
         * Instead, the test fixture was updated to use 127.0.0.1 when making HTTP requests.
         *
         * This allows tests that still want to exercise the deprecation logic to use http://localhost
         * which will bypass this check and trigger the validation.
         *
         * It's important to note that the only way to create a certificate for an IP address is to bind
         * the IP address as a 'Subject Alternative Name' which was deemed far too complicated for our test
         * use case.
         *
         * Additionally, in the rare case that a user or a plugin author truly needs to test with a localhost
         * server, they can use http://127.0.0.1
         */
        if ("127.0.0.1".equals(url.getHost())) {
            return true;
        }

        final String scheme = url.getScheme();
        return !"http".equalsIgnoreCase(scheme);
    }

    public static <T extends Collection<?>> T flatten(Object[] elements, T addTo, boolean flattenMaps) {
        return flatten(asList(elements), addTo, flattenMaps);
    }

    public static <T extends Collection<?>> T flatten(Object[] elements, T addTo) {
        return flatten(asList(elements), addTo);
    }

    public static <T extends Collection<?>> T flatten(Collection<?> elements, T addTo) {
        return flatten(elements, addTo, true);
    }

    public static <T extends Collection<?>> T flattenElements(Object... elements) {
        Collection<T> out = new LinkedList<T>();
        flatten(elements, out, true);
        return Cast.uncheckedNonnullCast(out);
    }

    public static <T extends Collection<?>> T flatten(Collection<?> elements, T addTo, boolean flattenMapsAndArrays) {
        return flatten(elements, addTo, flattenMapsAndArrays, flattenMapsAndArrays);
    }

    public static <T extends Collection<?>> T flatten(Collection<?> elements, T addTo, boolean flattenMaps, boolean flattenArrays) {
        for (Object element : elements) {
            if (element instanceof Collection) {
                flatten((Collection<?>) element, addTo, flattenMaps, flattenArrays);
            } else if ((element instanceof Map) && flattenMaps) {
                flatten(((Map<?, ?>) element).values(), addTo, flattenMaps, flattenArrays);
            } else if ((element.getClass().isArray()) && flattenArrays) {
                flatten(asList((Object[]) element), addTo, flattenMaps, flattenArrays);
            } else {
                (Cast.<Collection<Object>>uncheckedNonnullCast(addTo)).add(element);
            }
        }
        return addTo;
    }

    /**
     * Flattens input collections (including arrays *but* not maps). If input is not a collection wraps it in a collection and returns it.
     *
     * @param input any object
     * @return collection of flattened input or single input wrapped in a collection.
     */
    public static Collection<?> collectionize(Object input) {
        if (input == null) {
            return emptyList();
        } else if (input instanceof Collection) {
            Collection<?> out = new LinkedList<>();
            flatten((Collection<?>) input, out, false, true);
            return out;
        } else if (input.getClass().isArray()) {
            Collection<?> out = new LinkedList<>();
            flatten(asList((Object[]) input), out, false, true);
            return out;
        } else {
            return Collections.singletonList(input);
        }
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
