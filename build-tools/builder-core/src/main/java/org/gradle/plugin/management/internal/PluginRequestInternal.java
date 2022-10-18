package org.gradle.plugin.management.internal;

import org.gradle.plugin.management.PluginRequest;

public interface PluginRequestInternal extends PluginRequest {

    boolean isApply();

    Integer getLineNumber();

    String getScriptDisplayName();

    String getDisplayName();

    PluginRequestInternal getOriginalRequest();
}
