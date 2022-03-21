package com.tyron.builder.api.internal.snapshot;

/**
 * The case sensitivity of a file system.
 *
 * Note that the method for actually comparing paths with a case sensitivity are in {@link PathUtil} instead of being on this enum,
 * since it seems that the methods can be better inlined by the JIT compiler if they are static.
 */
public enum CaseSensitivity {
    CASE_SENSITIVE,
    CASE_INSENSITIVE
}