package com.tyron.builder.plugin;

import com.tyron.builder.api.provider.Property;

public interface CodeAssistPluginExtension {

    Property<Boolean> getIsR8Enabled();
}
