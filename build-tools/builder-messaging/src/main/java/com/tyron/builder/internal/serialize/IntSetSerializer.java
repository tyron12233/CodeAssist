package com.tyron.builder.internal.serialize;


import java.io.EOFException;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

public class IntSetSerializer implements Serializer<IntSet> {
    public static final IntSetSerializer INSTANCE = new IntSetSerializer();

    private IntSetSerializer() {
    }

    @Override
    public IntSet read(Decoder decoder) throws EOFException, Exception {
        int size = decoder.readInt();
        if (size == 0) {
            return IntSets.EMPTY_SET;
        }
        IntSet result = new IntOpenHashSet(size);
        for (int i = 0; i < size; i++) {
            result.add(decoder.readInt());
        }
        return result;
    }

    @Override
    public void write(Encoder encoder, IntSet value) throws Exception {
        encoder.writeInt(value.size());
        IntIterator iterator = value.iterator();
        while(iterator.hasNext()) {
            encoder.writeInt(iterator.nextInt());
        }
    }
}
