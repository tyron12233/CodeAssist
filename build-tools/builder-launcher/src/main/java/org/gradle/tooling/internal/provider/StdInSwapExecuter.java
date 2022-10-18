package org.gradle.tooling.internal.provider;

import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.Factory;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildActionResult;
import org.gradle.util.internal.StdinSwapper;

import java.io.InputStream;

class StdInSwapExecuter implements BuildActionExecuter<BuildActionParameters, BuildRequestContext> {
    private final InputStream standardInput;
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> embeddedExecutor;

    public StdInSwapExecuter(InputStream standardInput, BuildActionExecuter<BuildActionParameters, BuildRequestContext> embeddedExecutor) {
        this.standardInput = standardInput;
        this.embeddedExecutor = embeddedExecutor;
    }

    @Override
    public BuildActionResult execute(final BuildAction action, final BuildActionParameters actionParameters, final BuildRequestContext requestContext) {
        return new StdinSwapper().swap(standardInput, new Factory<BuildActionResult>() {
            @Override
            public BuildActionResult create() {
                return embeddedExecutor.execute(action, actionParameters, requestContext);
            }
        });
    }
}
