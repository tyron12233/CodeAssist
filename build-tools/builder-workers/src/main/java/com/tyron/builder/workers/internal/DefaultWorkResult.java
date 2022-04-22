package com.tyron.builder.workers.internal;

import com.tyron.builder.api.tasks.WorkResult;

import javax.annotation.Nullable;
import java.io.Serializable;

public class DefaultWorkResult implements WorkResult, Serializable {
    public static final DefaultWorkResult SUCCESS = new DefaultWorkResult(true, null);

    private final boolean didWork;
    private final Throwable exception;

    public DefaultWorkResult(boolean didWork, @Nullable Throwable exception) {
        this.didWork = didWork;
        this.exception = exception;
    }

    @Override
    public boolean getDidWork() {
        return didWork;
    }

    @Nullable
    public Throwable getException() {
        return exception;
    }

    public boolean isSuccess() {
        return exception == null;
    }
}

