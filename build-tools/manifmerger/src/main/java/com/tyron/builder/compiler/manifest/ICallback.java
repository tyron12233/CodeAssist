package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;

/**
 * Callback used by the merger to query the caller
 */
public interface ICallback {

    public static final int UNKNOWN_CODENAME = 0;

    public int queryCodenameApiLevel(@NotNull String codeName);
}
