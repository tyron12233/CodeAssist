package org.gradle.language.java.internal;

import static java.util.Collections.emptyList;

import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.internal.component.ComponentTypeRegistry;
import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import org.gradle.api.internal.tasks.compile.tooling.JavaCompileTaskSuccessResultPostProcessor;
import org.gradle.api.logging.configuration.LoggingConfiguration;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.internal.build.event.OperationResultPostProcessorFactory;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.jvm.JvmLibrary;
import org.gradle.jvm.toolchain.JavadocTool;
import org.gradle.jvm.toolchain.internal.JavaToolchain;
import org.gradle.jvm.toolchain.internal.ToolchainToolFactory;
import org.gradle.language.java.artifact.JavadocArtifact;
import org.gradle.process.internal.ExecActionFactory;
import org.gradle.tooling.events.OperationType;

import org.slf4j.LoggerFactory;

import java.util.Collections;

public class JavaLanguagePluginServiceRegistry extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new JavaGlobalScopeServices());
    }

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new JavaGradleScopeServices());
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.addProvider(new JavaProjectScopeServices());
    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.addProvider(new Object() {
            public AnnotationProcessorDetector createAnnotationProcessorDetector(FileContentCacheFactory cacheFactory, LoggingConfiguration loggingConfiguration) {
                return new AnnotationProcessorDetector(cacheFactory, LoggerFactory.getLogger(AnnotationProcessorDetector.class), loggingConfiguration.getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS);
            }
        });
    }

    private static class JavaGlobalScopeServices {
        OperationResultPostProcessorFactory createJavaSubscribableBuildActionRunnerRegistration() {
            return (clientSubscriptions, consumer) -> clientSubscriptions.isRequested(OperationType.TASK)
                ? Collections.singletonList(new JavaCompileTaskSuccessResultPostProcessor())
                : emptyList();
        }
    }

    private static class JavaGradleScopeServices {
        public void configure(ServiceRegistration registration, ComponentTypeRegistry componentTypeRegistry) {
            componentTypeRegistry.maybeRegisterComponentType(JvmLibrary.class)
                .registerArtifactType(JavadocArtifact.class, ArtifactType.JAVADOC);
        }
    }

    private static class JavaProjectScopeServices {

        public ToolchainToolFactory createToolFactory(ExecActionFactory generator) {
            return new ToolchainToolFactory() {
                @Override
                public <T> T create(Class<T> toolType, JavaToolchain toolchain) {
                    if (toolType == JavadocTool.class) {
//                        return toolType.cast(new JavadocToolAdapter(generator, toolchain));
                        throw new UnsupportedOperationException();
                    }
                    return null;
                }
            };
        }
    }
}
