package com.tyron.builder.plugin.management.internal;

import com.tyron.builder.plugin.management.PluginRequest;

public interface PluginRequestInternal extends PluginRequest {

    boolean isApply();

    Integer getLineNumber();

    String getScriptDisplayName();

    String getDisplayName();

    PluginRequestInternal getOriginalRequest();
}
