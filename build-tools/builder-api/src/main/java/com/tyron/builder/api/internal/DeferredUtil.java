package com.tyron.builder.api.internal;

import java.util.concurrent.Callable;

import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.providers.ProviderResolutionStrategy;

import org.jetbrains.annotations.Nullable;

public class DeferredUtil {

    /**
     * Successively unpacks a deferred value until it is resolved to null or something other than Callable (including Groovy Closure) or Kotlin lambda
     * then unpacks the remaining Provider or Factory.
     *
     * Fails when the Provider is not present.
     */
    @Nullable
    public static Object unpack(@Nullable Object deferred) {
        return unpack(ProviderResolutionStrategy.REQUIRE_PRESENT, deferred);
    }

    /**
     * Successively unpacks a deferred value until it is resolved to null or something other than Callable (including Groovy Closure) or Kotlin lambda
     * then unpacks the remaining Provider or Factory.
     *
     * Returns null when the provider is not present.
     */
    @Nullable
    public static Object unpackOrNull(@Nullable Object deferred) {
        return unpack(ProviderResolutionStrategy.ALLOW_ABSENT, deferred);
    }

    @Nullable
    private static Object unpack(ProviderResolutionStrategy providerResolutionStrategy, @Nullable Object deferred) {
        if (deferred == null) {
            return null;
        }
        Object value = unpackNestableDeferred(deferred);
        if (value instanceof Provider) {
            return providerResolutionStrategy.resolve((Provider<?>) value);
        }
        if (value instanceof Factory) {
            return ((Factory<?>) value).create();
        }
        return value;
    }

    public static boolean isDeferred(Object value) {
        return value instanceof Provider
               || value instanceof Factory
               || isNestableDeferred(value);
    }

    public static boolean isNestableDeferred(@Nullable Object value) {
        return value instanceof Callable
               || isKotlinFunction0Deferrable(value);
    }

    @Nullable
    public static Object unpackNestableDeferred(@Nullable Object deferred) {
        Object current = deferred;
        while (isNestableDeferred(current)) {
            if (current instanceof Callable) {
                current = (Callable<?>) current;
            } else {
                current = unpackKotlinFunction0(current);
            }
        }
        return current;
    }

    private static boolean isKotlinFunction0Deferrable(@Nullable Object value) {
        return value instanceof kotlin.jvm.functions.Function0;
    }

    @Nullable
    private static Object unpackKotlinFunction0(Object value) {
        return ((kotlin.jvm.functions.Function0) value).invoke();
    }
}