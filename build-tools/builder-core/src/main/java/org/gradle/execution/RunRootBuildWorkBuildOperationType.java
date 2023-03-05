package org.gradle.execution;

import org.gradle.internal.operations.BuildOperationType;

// Used by gradle-profiler
public interface RunRootBuildWorkBuildOperationType extends BuildOperationType<RunRootBuildWorkBuildOperationType.Details, Void> {
    class Details {
        private final long buildStartTime;

        public Details(long buildStartTime) {
            this.buildStartTime = buildStartTime;
        }

        public long getBuildStartTime() {
            return buildStartTime;
        }
    }
}
