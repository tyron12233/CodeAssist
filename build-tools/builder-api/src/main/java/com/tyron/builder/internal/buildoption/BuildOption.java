package com.tyron.builder.internal.buildoption;

import com.tyron.builder.cli.CommandLineParser;
import com.tyron.builder.cli.ParsedCommandLine;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Represents a option for a build provided by the user via Gradle property and/or a command line option.
 *
 * @param <T> the type of object that ultimately expresses the option to consumers
 * @since 4.3
 */
public interface BuildOption<T> {

    @Nullable
    String getGradleProperty();

    void applyFromProperty(Map<String, String> properties, T settings);

    void configure(CommandLineParser parser);

    void applyFromCommandLine(ParsedCommandLine options, T settings);

    abstract class Value<T> {
        public abstract boolean isExplicit();

        public abstract T get();

        /**
         * Creates the default value for an option.
         */
        public static <T> Value<T> defaultValue(final T value) {
            return new Value<T>() {
                @Override
                public boolean isExplicit() {
                    return false;
                }

                @Override
                public T get() {
                    return value;
                }
            };
        }

        /**
         * Creates an explicit value for an option.
         */
        public static <T> Value<T> value(final T value) {
            return new Value<T>() {
                @Override
                public boolean isExplicit() {
                    return true;
                }

                @Override
                public T get() {
                    return value;
                }
            };
        }
    }

}