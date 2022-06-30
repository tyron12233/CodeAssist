package com.tyron.builder.launcher.exec;

import com.tyron.builder.tooling.internal.provider.serialization.SerializedPayload;

import javax.annotation.Nullable;

/**
 * Encapsulates either a result object, or a failure as an exception or a serialized exception.
 *
 * <p>Exceptions should always be serialized, but currently are not when the failure happens outside the context of a build invocation because the serialization infrastructure is currently tied to some build scoped services.</p>
 */
public class BuildActionResult {
    private final SerializedPayload result;
    private final SerializedPayload serializedFailure;
    private final RuntimeException failure;
    private final boolean wasCancelled;

    private BuildActionResult(SerializedPayload result, SerializedPayload serializedFailure, RuntimeException failure, boolean wasCancelled) {
        this.result = result;
        this.serializedFailure = serializedFailure;
        this.failure = failure;
        this.wasCancelled = wasCancelled;
    }

    public static BuildActionResult of(@Nullable SerializedPayload result) {
        return new BuildActionResult(result, null, null, false);
    }

    public static BuildActionResult failed(SerializedPayload failure) {
        return new BuildActionResult(null, failure, null, false);
    }

    public static BuildActionResult failed(RuntimeException failure) {
        return new BuildActionResult(null, null, failure, false);
    }

    public static BuildActionResult cancelled(SerializedPayload failure) {
        return new BuildActionResult(null, failure, null, true);
    }

    public static BuildActionResult cancelled(RuntimeException failure) {
        return new BuildActionResult(null, null, failure, true);
    }

    public static BuildActionResult failed(boolean wasCancelled, @Nullable SerializedPayload failure, @Nullable RuntimeException exception) {
        return new BuildActionResult(null, failure, exception, wasCancelled);
    }

    /**
     * True when the build failed <em>and</em> was cancelled.
     */
    public boolean wasCancelled() {
        return wasCancelled;
    }

    @Nullable
    public SerializedPayload getResult() {
        return result;
    }

    @Nullable
    public SerializedPayload getFailure() {
        return serializedFailure;
    }

    @Nullable
    public RuntimeException getException() {
        return failure;
    }

    public boolean hasFailure() {
        return failure != null || serializedFailure != null;
    }

    public void rethrow() {
        if (failure != null) {
            throw failure;
        }
    }
}
