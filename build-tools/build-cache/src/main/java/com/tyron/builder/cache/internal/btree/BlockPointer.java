package com.tyron.builder.cache.internal.btree;

import com.google.common.primitives.Longs;

public class BlockPointer implements Comparable<BlockPointer> {

    private static final BlockPointer NULL = new BlockPointer(-1);

    public static BlockPointer start() {
        return NULL;
    }

    public static BlockPointer pos(long pos) {
        if (pos < -1) {
            throw new CorruptedCacheException("block pointer must be >= -1, but was" + pos);
        }
        if (pos == -1) {
            return NULL;
        }
        return new BlockPointer(pos);
    }

    private final long pos;

    private BlockPointer(long pos) {
        this.pos = pos;
    }

    public boolean isNull() {
        return pos < 0;
    }

    public long getPos() {
        return pos;
    }

    @Override
    public String toString() {
        return String.valueOf(pos);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        BlockPointer other = (BlockPointer) obj;
        return pos == other.pos;
    }

    @Override
    public int hashCode() {
        return Longs.hashCode(pos);
    }

    @Override
    public int compareTo(BlockPointer o) {
        return Longs.compare(pos, o.pos);
    }
}