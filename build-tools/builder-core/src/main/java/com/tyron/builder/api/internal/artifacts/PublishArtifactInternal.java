package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.PublishArtifact;

public interface PublishArtifactInternal extends PublishArtifact {
    boolean shouldBePublished();

    static boolean shouldBePublished(PublishArtifact artifact) {
        if (artifact instanceof PublishArtifactInternal) {
            return ((PublishArtifactInternal) artifact).shouldBePublished();
        }
        // This can happen for custom publish artifacts
        return true;
    }
}
