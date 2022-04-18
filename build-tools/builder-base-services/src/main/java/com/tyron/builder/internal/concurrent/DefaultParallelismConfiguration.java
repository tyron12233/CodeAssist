package com.tyron.builder.internal.concurrent;

import com.tyron.builder.concurrent.ParallelismConfiguration;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serializable;

public class DefaultParallelismConfiguration implements Serializable, ParallelismConfiguration {
    public static final ParallelismConfiguration DEFAULT = new DefaultParallelismConfiguration();

    private boolean parallelProjectExecution;
    private int maxWorkerCount;

    public DefaultParallelismConfiguration() {
        maxWorkerCount = Runtime.getRuntime().availableProcessors();
    }

    public DefaultParallelismConfiguration(boolean parallelProjectExecution, int maxWorkerCount) {
        this.parallelProjectExecution = parallelProjectExecution;
        this.maxWorkerCount = maxWorkerCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isParallelProjectExecutionEnabled() {
        return parallelProjectExecution;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setParallelProjectExecutionEnabled(boolean parallelProjectExecution) {
        this.parallelProjectExecution = parallelProjectExecution;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxWorkerCount() {
        return maxWorkerCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxWorkerCount(int maxWorkerCount) {
        if (maxWorkerCount < 1) {
            throw new IllegalArgumentException("Max worker count must be > 0");
        } else {
            this.maxWorkerCount = maxWorkerCount;
        }
    }

    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
}