package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.errors.EvalIssueException;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.model.SyncIssue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * An implementation of BaseConfig specifically for sending as part of the Android model
 * through the Gradle tooling API.
 */
@Immutable
public final class SyncIssueImpl implements SyncIssue, Serializable {
    private static final long serialVersionUID = 1L;

    private final int type;
    private final int severity;
    @Nullable private final String data;
    @NonNull private final String message;
    @Nullable private final List<String> multiLineMessage;

    public SyncIssueImpl(
            @NonNull IssueReporter.Type type,
            @NonNull IssueReporter.Severity severity,
            @NonNull EvalIssueException exception) {
        this(
                type,
                severity,
                exception.getData(),
                exception.getMessage(),
                exception.getMultlineMessage());
    }

    public SyncIssueImpl(
            @NonNull IssueReporter.Type type,
            @NonNull IssueReporter.Severity severity,
            @Nullable String data,
            @NonNull String message,
            List<String> multiLineMessage) {
        this.type = type.getType();
        this.severity = severity.getSeverity();
        this.data = data;
        this.message = message;
        this.multiLineMessage =
                multiLineMessage != null ? ImmutableList.copyOf(multiLineMessage) : null;
    }

    @Override
    public int getSeverity() {
        return severity;
    }

    @Override
    public int getType() {
        return type;
    }

    @Nullable
    @Override
    public String getData() {
        return data;
    }

    @NonNull
    @Override
    public String getMessage() {
        return message;
    }

    @Nullable
    @Override
    public List<String> getMultiLineMessage() {
        return multiLineMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SyncIssueImpl syncIssue = (SyncIssueImpl) o;
        return type == syncIssue.type
                && severity == syncIssue.severity
                && Objects.equals(data, syncIssue.data)
                && Objects.equals(message, syncIssue.message)
                && Objects.equals(multiLineMessage, syncIssue.multiLineMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, severity, data, message, multiLineMessage);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("type", type)
                .add("severity", severity)
                .add("data", data)
                .add("message", message)
                .toString();
    }
}
