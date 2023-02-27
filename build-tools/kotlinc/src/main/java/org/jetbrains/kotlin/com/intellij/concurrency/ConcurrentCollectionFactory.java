package org.jetbrains.kotlin.com.intellij.concurrency;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectHashMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectMap;
import org.jetbrains.kotlin.com.intellij.util.containers.HashingStrategy;

import java.util.concurrent.ConcurrentMap;

public class ConcurrentCollectionFactory {

    public static @NonNull <T, V> ConcurrentMap<T, V> createConcurrentMap(@NonNull HashingStrategy<? super T> hashStrategy) {
        return new ConcurrentHashMap<>(hashStrategy);
    }

    public static <V> ConcurrentIntObjectMap<V> createConcurrentIntObjectMap() {
        return new ConcurrentIntObjectHashMap<>();
    }
}
