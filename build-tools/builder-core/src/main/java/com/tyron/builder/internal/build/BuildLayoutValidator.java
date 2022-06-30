package com.tyron.builder.internal.build;

import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.UserInput;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.exceptions.FailureResolutionAware;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.configuration.project.BuiltInCommand;
import com.tyron.builder.initialization.BuildClientMetaData;
import com.tyron.builder.initialization.layout.BuildLayout;
import com.tyron.builder.initialization.layout.BuildLayoutConfiguration;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;

import java.util.List;

@ServiceScope(Scopes.BuildSession.class)
public class BuildLayoutValidator {
    private final BuildLayoutFactory buildLayoutFactory;
    private final DocumentationRegistry documentationRegistry;
    private final BuildClientMetaData clientMetaData;
    private final List<BuiltInCommand> builtInCommands;

    public BuildLayoutValidator(
            BuildLayoutFactory buildLayoutFactory,
            DocumentationRegistry documentationRegistry,
            BuildClientMetaData clientMetaData,
            List<BuiltInCommand> builtInCommands
    ) {
        this.buildLayoutFactory = buildLayoutFactory;
        this.documentationRegistry = documentationRegistry;
        this.clientMetaData = clientMetaData;
        this.builtInCommands = builtInCommands;
    }

    public void validate(StartParameterInternal startParameter) {
        BuildLayout buildLayout = buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(startParameter));
        if (!buildLayout.isBuildDefinitionMissing()) {
            // All good
            return;
        }

        for (BuiltInCommand command : builtInCommands) {
            if (command.commandLineMatches(startParameter.getTaskNames())) {
                // Allow missing settings and build scripts when running a built-in command
                return;
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("Directory '");
        message.append(startParameter.getCurrentDir());
        message.append("' does not contain a Gradle build.\n\n");
        message.append("A Gradle build should contain a 'settings.gradle' or 'settings.gradle.kts' file in its root directory. ");
        message.append("It may also contain a 'build.gradle' or 'build.gradle.kts' file.\n\n");
        message.append("To create a new Gradle build in this directory run '");
        clientMetaData.describeCommand(message, "init");
        message.append("'\n\n");
        message.append("For more detail on the 'init' task see ");
        message.append(documentationRegistry.getDocumentationFor("build_init_plugin"));
        message.append("\n\n");
        message.append("For more detail on creating a Gradle build see ");
        message.append(documentationRegistry.getDocumentationFor("tutorial_using_tasks")); // this is the "build script basics" chapter, we're missing some kind of "how to write a Gradle build chapter"
        throw new BuildLayoutException(message.toString());
    }

    private static class BuildLayoutException extends BuildException implements FailureResolutionAware {
        public BuildLayoutException(String message) {
            super(message);
        }

        @Override
        public void appendResolutions(Context context) {
            context.doNotSuggestResolutionsThatRequireBuildDefinition();
            context.appendResolution(output -> {
                output.text("Run ");
                context.getClientMetaData().describeCommand(output.withStyle(UserInput), "init");
                output.text(" to create a new Gradle build in this directory.");
            });
        }
    }
}