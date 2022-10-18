package org.gradle.api.internal.artifacts;

import org.gradle.api.artifacts.PublishArtifact;

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
