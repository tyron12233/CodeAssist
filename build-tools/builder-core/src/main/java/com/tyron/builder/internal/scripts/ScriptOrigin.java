package com.tyron.builder.internal.scripts;

/**
 * This interface is implemented by remapped build scripts.
 */
public interface ScriptOrigin {

    /**
     * Returns the non-remapped class name.
     */
    String getOriginalClassName();

    /**
     * Returns the hash of the bytecode of the non-remapped class.
     */
    String getContentHash();
}
