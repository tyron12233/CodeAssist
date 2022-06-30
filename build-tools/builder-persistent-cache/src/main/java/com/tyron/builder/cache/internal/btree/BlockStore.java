package com.tyron.builder.cache.internal.btree;

public interface BlockStore {
    /**
     * Opens this store, calling the given action if the store is empty.
     */
    void open(Runnable initAction, Factory factory);

    /**
     * Closes this store.
     */
    void close();

    /**
     * Discards all blocks from this store.
     */
    void clear();

    /**
     * Removes the given block from this store.
     */
    void remove(BlockPayload block);

    /**
     * Reads the first block from this store.
     */
    <T extends BlockPayload> T readFirst(Class<T> payloadType);

    /**
     * Reads a block from this store.
     */
    <T extends BlockPayload> T read(BlockPointer pos, Class<T> payloadType);

    /**
     * Writes a block to this store, adding the block if required.
     */
    void write(BlockPayload block);

    /**
     * Adds a new block to this store. Allocates space for the block, but does not write the contents of the block
     * until {@link #write(BlockPayload)} is called.
     */
    void attach(BlockPayload block);

    /**
     * Flushes any pending updates for this store.
     */
    void flush();

    interface Factory {
        Object create(Class<? extends BlockPayload> type);
    }
}