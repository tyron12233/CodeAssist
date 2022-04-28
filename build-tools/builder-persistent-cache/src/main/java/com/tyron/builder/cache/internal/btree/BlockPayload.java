package com.tyron.builder.cache.internal.btree;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public abstract class BlockPayload {
    private Block block;

    public Block getBlock() {
        return block;
    }

    public void setBlock(Block block) {
        this.block = block;
    }

    public BlockPointer getPos() {
        return getBlock().getPos();
    }

    public BlockPointer getNextPos() {
        return getBlock().getNextPos();
    }

    protected abstract int getSize();

    protected abstract byte getType();

    protected abstract void read(DataInputStream inputStream) throws Exception;

    protected abstract void write(DataOutputStream outputStream) throws Exception;

    protected RuntimeException blockCorruptedException() {
        return getBlock().blockCorruptedException();
    }
}