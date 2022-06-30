package com.tyron.builder.cache.internal.btree;

public abstract class Block {
    static final int LONG_SIZE = 8;
    static final int INT_SIZE = 4;
    static final int SHORT_SIZE = 2;

    private BlockPayload payload;

    protected Block(BlockPayload payload) {
        this.payload = payload;
        payload.setBlock(this);
    }

    public BlockPayload getPayload() {
        return payload;
    }

    protected void detach() {
        payload.setBlock(null);
        payload = null;
    }

    public abstract BlockPointer getPos();

    public abstract int getSize();

    public abstract RuntimeException blockCorruptedException();

    @Override
    public String toString() {
        return payload.getClass().getSimpleName() + " " + getPos();
    }

    public BlockPointer getNextPos() {
        return BlockPointer.pos(getPos().getPos() + getSize());
    }

    public abstract boolean hasPos();

    public abstract void setPos(BlockPointer pos);

    public abstract void setSize(int size);
}