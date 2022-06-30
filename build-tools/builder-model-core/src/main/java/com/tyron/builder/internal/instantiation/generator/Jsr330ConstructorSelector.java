package com.tyron.builder.internal.instantiation.generator;

import com.tyron.builder.cache.internal.CrossBuildInMemoryCache;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.logging.text.TreeFormatter;

import java.lang.reflect.Modifier;

class Jsr330ConstructorSelector implements ConstructorSelector {
    private final CrossBuildInMemoryCache<Class<?>, CachedConstructor> constructorCache;
    private final ClassGenerator classGenerator;

    public Jsr330ConstructorSelector(ClassGenerator classGenerator, CrossBuildInMemoryCache<Class<?>, CachedConstructor> constructorCache) {
        this.constructorCache = constructorCache;
        this.classGenerator = classGenerator;
    }

    @Override
    public void vetoParameters(ClassGenerator.GeneratedConstructor<?> constructor, Object[] parameters) {
        for (Object param : parameters) {
            if (param == null) {
                TreeFormatter formatter = new TreeFormatter();
                formatter.node("Null value provided in parameters ");
                formatter.appendValues(parameters);
                throw new IllegalArgumentException(formatter.toString());
            }
        }
    }

    @Override
    public <T> ClassGenerator.GeneratedConstructor<? extends T> forParams(Class<T> type, Object[] params) {
        return forType(type);
    }

    @Override
    public <T> ClassGenerator.GeneratedConstructor<? extends T> forType(final Class<T> type) throws UnsupportedOperationException {
        CachedConstructor constructor = constructorCache.get(type, () -> {
            try {
                validateType(type);
                ClassGenerator.GeneratedClass<?> implClass = classGenerator.generate(type);
                ClassGenerator.GeneratedConstructor<?> generatedConstructor = InjectUtil.selectConstructor(implClass, type);
                return CachedConstructor.of(generatedConstructor);
            } catch (RuntimeException e) {
                return CachedConstructor.of(e);
            }
        });
        return Cast.uncheckedCast(constructor.getConstructor());
    }

    private static <T> void validateType(Class<T> type) {
        if (!type.isInterface() && type.getEnclosingClass() != null && !Modifier.isStatic(type.getModifiers())) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node(type);
            formatter.append(" is a non-static inner class.");
            throw new IllegalArgumentException(formatter.toString());
        }
    }

    public static class CachedConstructor {
        private final ClassGenerator.GeneratedConstructor<?> constructor;
        private final RuntimeException error;

        private CachedConstructor(ClassGenerator.GeneratedConstructor<?> constructor, RuntimeException error) {
            this.constructor = constructor;
            this.error = error;
        }

        public ClassGenerator.GeneratedConstructor<?> getConstructor() {
            if (error != null) {
                throw error;
            }
            return constructor;
        }

        public static CachedConstructor of(ClassGenerator.GeneratedConstructor<?> ctor) {
            return new CachedConstructor(ctor, null);
        }

        public static CachedConstructor of(RuntimeException err) {
            return new CachedConstructor(null, err);
        }
    }
}
