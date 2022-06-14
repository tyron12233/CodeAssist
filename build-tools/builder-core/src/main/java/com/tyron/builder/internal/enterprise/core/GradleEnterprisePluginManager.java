package com.tyron.builder.internal.enterprise.core;


import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.InternalBuildAdapter;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@ServiceScope(Scopes.BuildTree.class)
public class GradleEnterprisePluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradleEnterprisePluginManager.class);

    @VisibleForTesting
    public static final String NO_SCAN_PLUGIN_MSG = "An internal error occurred that prevented a build scan from being created.\n" +
                                                    "Please report this via https://github.com/gradle/gradle/issues";

    public static final String OLD_SCAN_PLUGIN_VERSION_MESSAGE =
            "The build scan plugin is not compatible with this version of Gradle.\n"
            + "Please see https://gradle.com/help/gradle-6-build-scan-plugin for more information.";

    @Nullable
    private GradleEnterprisePluginAdapter adapter;

    // Indicates plugin checked in, but was unsupported
    private boolean unsupported;

    @Nullable
    public GradleEnterprisePluginAdapter getAdapter() {
        return adapter;
    }

    public void registerAdapter(GradleEnterprisePluginAdapter adapter) {
        if (unsupported) {
            throw new IllegalStateException("plugin already noted as unsupported");
        }
        this.adapter = adapter;
    }

    public void unsupported() {
        if (adapter != null) {
            throw new IllegalStateException("plugin already noted as supported");
        }
        this.unsupported = true;
    }

    public boolean isPresent() {
        return adapter != null;
    }

    public void buildFinished(@Nullable Throwable buildFailure) {
        if (adapter != null) {
            adapter.buildFinished(buildFailure);
        }
    }

    /**
     * This should never happen due to the auto apply behavior.
     * It's only here as a kind of safeguard or fallback.
     */
    public void registerMissingPluginWarning(GradleInternal gradle) {
        if (gradle.isRootBuild()) {
            StartParameter startParameter = gradle.getStartParameter();
            boolean requested = !startParameter.isNoBuildScan() && startParameter.isBuildScan();
            if (requested) {
                gradle.addListener(new InternalBuildAdapter() {
                    @Override
                    public void settingsEvaluated(@Nonnull Settings settings) {
                        if (!isPresent() && !unsupported) {
                            LOGGER.warn(NO_SCAN_PLUGIN_MSG);
                        }
                    }
                });
            }
        }
    }

}
