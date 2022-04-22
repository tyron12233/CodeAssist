package com.tyron.builder.internal.serialize;

import com.google.common.base.Objects;

import java.io.EOFException;
import java.util.Collection;

public abstract class AbstractCollectionSerializer<T, C extends Collection<T>> implements Serializer<C> {
    protected final Serializer<T> entrySerializer;

    public AbstractCollectionSerializer(Serializer<T> entrySerializer) {
        this.entrySerializer = entrySerializer;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }

        AbstractCollectionSerializer<?, ?> rhs = (AbstractCollectionSerializer<?, ?>) obj;
        return Objects.equal(entrySerializer, rhs.entrySerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass(), entrySerializer);
    }

    protected abstract C createCollection(int size);

    @Override
    public C read(Decoder decoder) throws EOFException, Exception {
        int size = decoder.readInt();
        C values = createCollection(size);
        for (int i = 0; i < size; i++) {
            values.add(entrySerializer.read(decoder));
        }
        return values;
    }

    @Override
    public void write(Encoder encoder, C value) throws Exception {
        encoder.writeInt(value.size());
        for (T t : value) {
            entrySerializer.write(encoder, t);
        }
    }

}

