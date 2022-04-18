package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.component.BuildIdentifier;

/**
 * A build that is not the current build. This type exists only to provide an answer to {@link #isCurrentBuild()}, which should not exist.
 */
public class ForeignBuildIdentifier extends DefaultBuildIdentifier {
    private final String legacyName;

    public ForeignBuildIdentifier(String name, String legacyName) {
        super(name);
        this.legacyName = legacyName;
    }

    @Override
    public String getName() {
        return legacyName;
    }

    @Override
    public boolean isCurrentBuild() {
        return false;
    }
}