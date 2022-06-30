package com.tyron.builder.configuration;

import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.configuration.internal.UserCodeApplicationId;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.operations.BuildOperation;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.resource.ResourceLocation;
import com.tyron.builder.internal.resource.TextResource;

import javax.annotation.Nullable;
import java.io.File;
import java.net.URI;

/**
 * A decorating {@link ScriptPlugin} implementation that delegates to a given
 * delegatee implementation, but wraps the apply() execution in a
 * {@link BuildOperation}.
 */
public class BuildOperationScriptPlugin implements ScriptPlugin {

    private final ScriptPlugin decorated;
    private final BuildOperationExecutor buildOperationExecutor;
    private final UserCodeApplicationContext userCodeApplicationContext;

    public BuildOperationScriptPlugin(ScriptPlugin decorated, BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext) {
        this.decorated = decorated;
        this.buildOperationExecutor = buildOperationExecutor;
        this.userCodeApplicationContext = userCodeApplicationContext;
    }

    @Override
    public ScriptSource getSource() {
        return decorated.getSource();
    }

    @Override
    public void apply(final Object target) {
        TextResource resource = getSource().getResource();
        if (resource.isContentCached() && resource.getHasEmptyContent()) {
            //no operation, if there is no script code provided
            decorated.apply(target);
        } else {
            userCodeApplicationContext.apply(getSource().getShortDisplayName(), userCodeApplicationId -> buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) {
                    decorated.apply(target);
                    context.setResult(OPERATION_RESULT);
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    final ScriptSource source = getSource();
                    final ResourceLocation resourceLocation = source.getResource().getLocation();
                    final File file = resourceLocation.getFile();
                    String name = "Apply " + source.getShortDisplayName();
                    final String displayName = name + " to " + target;

                    return BuildOperationDescriptor.displayName(displayName)
                        .name(name)
                        .details(new OperationDetails(file, resourceLocation, ConfigurationTargetIdentifier.of(target), userCodeApplicationId));
                }
            }));
        }
    }

    private static class OperationDetails implements ApplyScriptPluginBuildOperationType.Details {

        private final File file;
        private final ResourceLocation resourceLocation;
        private final ConfigurationTargetIdentifier identifier;
        private final UserCodeApplicationId applicationId;

        private OperationDetails(File file, ResourceLocation resourceLocation, @Nullable ConfigurationTargetIdentifier identifier, UserCodeApplicationId applicationId) {
            this.file = file;
            this.resourceLocation = resourceLocation;
            this.identifier = identifier;
            this.applicationId = applicationId;
        }

        @Override
        @Nullable
        public String getFile() {
            return file == null ? null : file.getAbsolutePath();
        }

        @Nullable
        @Override
        public String getUri() {
            if (file == null) {
                URI uri = resourceLocation.getURI();
                return uri == null ? null : uri.toASCIIString();
            } else {
                return null;
            }
        }

        @Override
        public String getTargetType() {
            return identifier == null ? null : identifier.getTargetType().label;
        }

        @Nullable
        @Override
        public String getTargetPath() {
            return identifier == null ? null : identifier.getTargetPath();
        }

        @Override
        public String getBuildPath() {
            return identifier == null ? null : identifier.getBuildPath();
        }

        @Override
        public long getApplicationId() {
            return applicationId.longValue();
        }
    }


    private static final ApplyScriptPluginBuildOperationType.Result OPERATION_RESULT = new ApplyScriptPluginBuildOperationType.Result() {
    };
}
