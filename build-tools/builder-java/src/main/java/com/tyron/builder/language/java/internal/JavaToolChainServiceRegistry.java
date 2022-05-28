package com.tyron.builder.language.java.internal;

import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.internal.tasks.compile.DefaultJavaCompilerFactory;
import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.jvm.toolchain.internal.JavaCompilerFactory;
import com.tyron.builder.process.internal.ExecHandleFactory;
import com.tyron.builder.process.internal.JavaForkOptionsFactory;
import com.tyron.builder.process.internal.worker.child.WorkerDirectoryProvider;
import com.tyron.builder.workers.internal.ActionExecutionSpecFactory;
import com.tyron.builder.workers.internal.WorkerDaemonFactory;

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
