package org.jetbrains.kotlin.com.intellij.util.containers;

import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.function.Predicate;

public final class BiDirectionalEnumerator<T> extends Enumerator<T> {
    @NonNull
    private final Int2ObjectMap<T> myIntToObjectMap;

    public BiDirectionalEnumerator(int expectNumber) {
        super(expectNumber);

        myIntToObjectMap = new Int2ObjectOpenHashMap<>(expectNumber);
    }

    @Override
    public int enumerateImpl(T object) {
        int index = super.enumerateImpl(object);
        myIntToObjectMap.put(Math.max(index, -index), object);
        return index;
    }

//    @Override
    public void clear() {
//        super.clear();
        myIntToObjectMap.clear();
    }

    public boolean contains(T t) {
        for (T value : myIntToObjectMap.values()) {
            if (t == value) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public T getValue(int index) {
        T value = myIntToObjectMap.get(index);
        if (value == null) {
            throw new RuntimeException("Can not find value by index " + index);
        }
        return value;
    }

    public void forEachValue(@NonNull Predicate<? super T> processor) {
        for (T value : myIntToObjectMap.values()) {
            if (!processor.test(value)) {
                break;
            }
        }
    }
}