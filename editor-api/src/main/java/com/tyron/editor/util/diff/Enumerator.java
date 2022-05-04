package com.tyron.editor.util.diff;

import org.jetbrains.annotations.NotNull;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public class Enumerator<T> {
//    private static final LoggerRt LOG = LoggerRt.getInstance(Enumerator.class);
    private final Object2IntMap<T> myNumbers;
    private int myNextNumber = 1;

    public Enumerator(int expectNumber) {
        myNumbers = new Object2IntOpenHashMap<>(expectNumber);
    }

    public void clear() {
        myNumbers.clear();
        myNextNumber = 1;
    }

    public int @NotNull [] enumerate(T @NotNull [] objects) {
        return enumerate(objects, 0, 0);
    }

    public int @NotNull [] enumerate(T @NotNull [] objects,
                                     final int startShift,
                                     final int endCut) {
        int[] idx = new int[objects.length - startShift - endCut];
        for (int i = startShift; i < objects.length - endCut; i++) {
            final T object = objects[i];
            final int number = enumerate(object);
            idx[i - startShift] = number;
        }
        return idx;
    }

    public int enumerate(T object) {
        final int res = enumerateImpl(object);
        return Math.max(res, -res);
    }

    public boolean add(T object) {
        final int res = enumerateImpl(object);
        return res < 0;
    }

    public int enumerateImpl(T object) {
        if (object == null) {
            return 0;
        }

        int number = myNumbers.getInt(object);
        if (number == 0) {
            number = myNextNumber++;
            myNumbers.put(object, number);
            return -number;
        }
        return number;
    }

    public boolean contains(@NotNull T object) {
        return myNumbers.getInt(object) != 0;
    }

    public int get(T object) {
        if (object == null) {
            return 0;
        }
        final int res = myNumbers.getInt(object);

        if (res == 0) {
//            LOG.error("Object " + object + " must be already added to enumerator!");
        }

        return res;
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        for (Object2IntMap.Entry<T> entry : myNumbers.object2IntEntrySet()) {
            buffer.append(entry.getIntValue()).append(": ").append(entry.getKey()).append("\n");
        }
        return buffer.toString();
    }
}