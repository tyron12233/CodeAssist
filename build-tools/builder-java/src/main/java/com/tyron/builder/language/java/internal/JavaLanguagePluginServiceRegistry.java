package com.tyron.builder.language.java.internal;

import static java.util.Collections.emptyList;

import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.api.internal.component.ComponentTypeRegistry;
import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import com.tyron.builder.api.internal.tasks.compile.tooling.JavaCompileTaskSuccessResultPostProcessor;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.cache.internal.FileContentCacheFactory;
import com.tyron.builder.internal.build.event.OperationResultPostProcessorFactory;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.jvm.JvmLibrary;
import com.tyron.builder.jvm.toolchain.JavadocTool;
import com.tyron.builder.jvm.toolchain.internal.JavaToolchain;
import com.tyron.builder.jvm.toolchain.internal.ToolchainToolFactory;
import com.tyron.builder.language.java.artifact.JavadocArtifact;
import com.tyron.builder.process.internal.ExecActionFactory;
import com.tyron.builder.tooling.events.OperationType;

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
