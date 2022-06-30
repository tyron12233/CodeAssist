package com.tyron.builder.plugin.management.internal;

import com.tyron.builder.api.artifacts.ModuleVersionSelector;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.plugin.use.PluginId;
import com.tyron.builder.plugin.use.internal.DefaultPluginId;

import javax.annotation.Nullable;

public class DefaultPluginRequest implements PluginRequestInternal {

    private final PluginId id;
    private final String version;
    private final boolean apply;
    private final Integer lineNumber;
    private final String scriptDisplayName;
    private final ModuleVersionSelector artifact;
    private final PluginRequestInternal originalRequest;

    public DefaultPluginRequest(PluginId id, String version, boolean apply, Integer lineNumber, ScriptSource scriptSource) {
        this(id, version, apply, lineNumber, scriptSource.getDisplayName(), null);
    }

    public DefaultPluginRequest(String id, String version, boolean apply, Integer lineNumber, String scriptDisplayName) {
        this(DefaultPluginId.of(id), version, apply, lineNumber, scriptDisplayName, null);
    }

    public DefaultPluginRequest(PluginId id, String version, boolean apply, Integer lineNumber, String scriptDisplayName, ModuleVersionSelector artifact) {
        this(id, version, apply, lineNumber, scriptDisplayName, artifact, null);
    }

    public DefaultPluginRequest(PluginId id, String version, boolean apply, Integer lineNumber, String scriptDisplayName, ModuleVersionSelector artifact,
                                PluginRequestInternal originalRequest) {
        this.id = id;
        this.version = version;
        this.apply = apply;
        this.lineNumber = lineNumber;
        this.scriptDisplayName = scriptDisplayName;
        this.artifact = artifact;
        this.originalRequest = originalRequest != null ? originalRequest : this;
    }

    @Override
    public PluginId getId() {
        return id;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Nullable
    @Override
    public ModuleVersionSelector getModule() {
        return artifact;
    }

    @Override
    public boolean isApply() {
        return apply;
    }

    @Override
    public Integer getLineNumber() {
        return lineNumber;
    }

    @Override
    public String getScriptDisplayName() {
        return scriptDisplayName;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("[id: '").append(id).append("'");
        if (version != null) {
            b.append(", version: '").append(version).append("'");
        }
        if (artifact != null) {
            b.append(", artifact: '").append(artifact).append("'");
        }
        if (!apply) {
            b.append(", apply: false");
        }

        b.append("]");
        return b.toString();
    }

    @Override
    public String getDisplayName() {
        return toString();
    }

    @Override
    public PluginRequestInternal getOriginalRequest() {
        return originalRequest;
    }
}
