package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.groovy.scripts.TextResourceScriptSource;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.configuration.InitScriptProcessor;
import com.tyron.builder.internal.resource.TextFileResourceLoader;
import com.tyron.builder.internal.resource.TextResource;

import java.io.File;
import java.util.List;

/**
 * Finds and executes all init scripts for a given build.
 */
public class InitScriptHandler {
    private final InitScriptProcessor processor;
    private final BuildOperationExecutor buildOperationExecutor;
    private final TextFileResourceLoader resourceLoader;

    public InitScriptHandler(InitScriptProcessor processor, BuildOperationExecutor buildOperationExecutor, TextFileResourceLoader resourceLoader) {
        this.processor = processor;
        this.buildOperationExecutor = buildOperationExecutor;
        this.resourceLoader = resourceLoader;
    }

    public void executeScripts(final GradleInternal gradle) {
        final List<File> initScripts = gradle.getStartParameter().getAllInitScripts();
        if (initScripts.isEmpty()) {
            return;
        }

        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                for (File script : initScripts) {
                    TextResource resource = resourceLoader.loadFile("initialization script", script);
                    processor.process(new TextResourceScriptSource(resource), gradle);
                }
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Run init scripts").progressDisplayName("Running init scripts");
            }
        });
    }
}