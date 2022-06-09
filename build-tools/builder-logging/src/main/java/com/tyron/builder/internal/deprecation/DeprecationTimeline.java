package com.tyron.builder.internal.deprecation;

import com.tyron.builder.util.GradleVersion;

class DeprecationTimeline {
    private final String messagePattern;
    private final GradleVersion targetVersion;

    private DeprecationTimeline(String messagePattern, GradleVersion targetVersion) {
        this.messagePattern = messagePattern;
        this.targetVersion = targetVersion;
    }

    static DeprecationTimeline willBeRemovedInVersion(GradleVersion version) {
        return new DeprecationTimeline("This is scheduled to be removed in %s.", version);
    }

    static DeprecationTimeline willBecomeAnErrorInVersion(GradleVersion version) {
        return new DeprecationTimeline("This will fail with an error in %s.", version);
    }

    static DeprecationTimeline behaviourWillBeRemovedInVersion(GradleVersion version) {
        return new DeprecationTimeline(
                "This behaviour has been deprecated and is scheduled to be removed in %s.",
                version);
    }

    @Override
    public String toString() {
        return String.format(messagePattern, targetVersion);
    }
}
