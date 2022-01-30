package com.tyron.builder.compiler.manifest;

import androidx.annotation.NonNull;

/**
 * Callback used by the merger to query the caller
 */
public interface ICallback {

    public static final int UNKNOWN_CODENAME = 0;

    public int queryCodenameApiLevel(@NonNull String codeName);
}
