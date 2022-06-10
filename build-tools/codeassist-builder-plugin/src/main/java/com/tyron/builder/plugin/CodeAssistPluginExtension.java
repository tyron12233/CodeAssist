package com.tyron.builder.plugin;

import org.gradle.api.provider.Property;

public interface CodeAssistPluginExtension {

    Property<Boolean> getIsR8Enabled();
}
