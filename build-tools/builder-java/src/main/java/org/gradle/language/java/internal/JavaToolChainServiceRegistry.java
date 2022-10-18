package org.gradle.language.java.internal;

import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.tasks.compile.DefaultJavaCompilerFactory;
import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.jvm.toolchain.internal.JavaCompilerFactory;
import org.gradle.process.internal.ExecHandleFactory;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.worker.child.WorkerDirectoryProvider;
import org.gradle.workers.internal.ActionExecutionSpecFactory;
import org.gradle.workers.internal.WorkerDaemonFactory;

public class JavaToolChainServiceRegistry extends AbstractPluginServiceRegistry {
    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.addProvider(new ProjectScopeCompileServices());
    }

    private static class ProjectScopeCompileServices {
        JavaCompilerFactory createJavaCompilerFactory(WorkerDaemonFactory workerDaemonFactory, JavaForkOptionsFactory forkOptionsFactory, WorkerDirectoryProvider workerDirectoryProvider, ExecHandleFactory execHandleFactory, AnnotationProcessorDetector processorDetector, ClassPathRegistry classPathRegistry, ActionExecutionSpecFactory actionExecutionSpecFactory) {
            return new DefaultJavaCompilerFactory(workerDirectoryProvider, workerDaemonFactory, forkOptionsFactory, execHandleFactory, processorDetector, classPathRegistry, actionExecutionSpecFactory);
        }

    }
}
