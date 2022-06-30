package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderId;

import java.util.Arrays;

/**
 * Provides implementations of classloader ids
 */
public class ClassLoaderIds {

    public enum Type {
        SCRIPT,
        TEST_TASK_CLASSPATH
    }

    private static ClassLoaderId of(Type type, String... attributes) {
        return new DefaultClassLoaderId(type, attributes);
    }

    public static ClassLoaderId buildScript(String fileName, String operationId) {
        return of(Type.SCRIPT, fileName, operationId);
    }

    public static ClassLoaderId testTaskClasspath(String testTaskPath) {
        return of(Type.TEST_TASK_CLASSPATH, testTaskPath);
    }

    private static class DefaultClassLoaderId implements ClassLoaderId {
        private final Type type;
        private final String[] attributes;

        public DefaultClassLoaderId(Type type, String[] attributes) {
            this.type = type;
            this.attributes = attributes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            DefaultClassLoaderId that = (DefaultClassLoaderId) o;

            return type == that.type && Arrays.equals(attributes, that.attributes);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + Arrays.hashCode(attributes);
            return result;
        }

        @Override
        public String getDisplayName() {
            return type + "[" + Arrays.toString(attributes) + "]";
        }

        @Override
        public String toString() {
            return "ClassLoaderId{type=" + type + ", attributes=" + Arrays.toString(attributes) + '}';
        }
    }
}
