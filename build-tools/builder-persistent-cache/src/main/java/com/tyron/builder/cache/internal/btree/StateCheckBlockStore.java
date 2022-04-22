package com.tyron.builder.cache.internal.btree;

public class StateCheckBlockStore implements BlockStore {
    private final BlockStore blockStore;
    private boolean open;

    public StateCheckBlockStore(BlockStore blockStore) {
        this.blockStore = blockStore;
    }

    @Override
    public void open(Runnable initAction, Factory factory) {
        assert !open;
        open = true;
        blockStore.open(initAction, factory);
    }

    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        blockStore.close();
    }

    @Override
    public void clear() {
        assert open;
        blockStore.clear();
    }

    @Override
    public void remove(BlockPayload block) {
        assert open;
        blockStore.remove(block);
    }

    @Override
    public <T extends BlockPayload> T readFirst(Class<T> payloadType) {
        assert open;
        return blockStore.readFirst(payloadType);
    }

    @Override
    public <T extends BlockPayload> T read(BlockPointer pos, Class<T> payloadType) {
        assert open;
        return blockStore.read(pos, payloadType);
    }

    @Override
    public void write(BlockPayload block) {
        assert open;
        blockStore.write(block);
    }

    @Override
    public void attach(BlockPayload block) {
        assert open;
        blockStore.attach(block);
    }

    @Override
    public void flush() {
        assert open;
        blockStore.flush();
    }
}