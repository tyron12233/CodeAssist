package com.tyron.builder.internal.deprecation;

public enum ConfigurationDeprecationType {
    DEPENDENCY_DECLARATION("use", true),
    CONSUMPTION("use attributes to consume", false),
    RESOLUTION("resolve", true),
    ARTIFACT_DECLARATION("use", true);

    public final String usage;
    public final boolean inUserCode;

    ConfigurationDeprecationType(String usage, boolean inUserCode) {
        this.usage = usage;
        this.inUserCode = inUserCode;
    }

    public String displayName() {
        return name().toLowerCase().replace('_', ' ');
    }
}
