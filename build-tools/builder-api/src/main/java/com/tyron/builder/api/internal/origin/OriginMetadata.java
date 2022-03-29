package com.tyron.builder.api.internal.origin;

import java.time.Duration;

public class OriginMetadata {

    private final String buildInvocationId;
    private final Duration executionTime;

    public OriginMetadata(String buildInvocationId, Duration executionTime) {
        this.buildInvocationId = buildInvocationId;
        this.executionTime = executionTime;
    }

    public String getBuildInvocationId() {
        return buildInvocationId;
    }

    public Duration getExecutionTime() {
        return executionTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        OriginMetadata that = (OriginMetadata) o;

        if (!buildInvocationId.equals(that.buildInvocationId)) {
            return false;
        }
        return executionTime.equals(that.executionTime);
    }

    @Override
    public int hashCode() {
        int result = buildInvocationId.hashCode();
        result = 31 * result + executionTime.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "OriginMetadata{"
               + "buildInvocationId=" + buildInvocationId
               + ", executionTime=" + executionTime
               + '}';
    }
}