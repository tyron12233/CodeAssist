package com.tyron.builder.api.internal.tasks.compile.daemon;

import com.google.common.collect.Lists;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.compile.BaseForkOptions;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.classloader.ClassLoaderUtils;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.language.base.internal.compile.CompileSpec;
import com.tyron.builder.language.base.internal.compile.Compiler;
import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;
import com.tyron.builder.workers.internal.ActionExecutionSpecFactory;
import com.tyron.builder.workers.internal.BuildOperationAwareWorker;
import com.tyron.builder.workers.internal.DaemonForkOptions;
import com.tyron.builder.workers.internal.DefaultWorkResult;
import com.tyron.builder.workers.internal.ForkedWorkerRequirement;
import com.tyron.builder.workers.internal.IsolatedClassLoaderWorkerRequirement;
import com.tyron.builder.workers.internal.IsolationMode;
import com.tyron.builder.workers.internal.ProvidesWorkResult;
import com.tyron.builder.workers.internal.WorkerFactory;

import javax.inject.Inject;
import java.io.Serializable;
import java.util.Set;

import static com.tyron.builder.process.internal.util.MergeOptionsUtil.mergeHeapSize;
import static com.tyron.builder.process.internal.util.MergeOptionsUtil.normalized;

public abstract class AbstractDaemonCompiler<T extends CompileSpec> implements Compiler<T> {
    private final WorkerFactory workerFactory;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;

    public AbstractDaemonCompiler(WorkerFactory workerFactory, ActionExecutionSpecFactory actionExecutionSpecFactory) {
        this.workerFactory = workerFactory;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
    }

    @Override
    public WorkResult execute(T spec) {
        IsolatedClassLoaderWorkerRequirement workerRequirement = getWorkerRequirement(workerFactory.getIsolationMode(), spec);
        BuildOperationAwareWorker worker = workerFactory.getWorker(workerRequirement);

        CompilerParameters parameters = getCompilerParameters(spec);
        DefaultWorkResult result = worker.execute(actionExecutionSpecFactory.newIsolatedSpec("compiler daemon", CompilerWorkAction.class, parameters, workerRequirement, true));
        if (result.isSuccess()) {
            return result;
        } else {
            throw UncheckedException.throwAsUncheckedException(result.getException());
        }
    }

    private IsolatedClassLoaderWorkerRequirement getWorkerRequirement(IsolationMode isolationMode, T spec) {
        DaemonForkOptions daemonForkOptions = toDaemonForkOptions(spec);
        switch (isolationMode) {
            case CLASSLOADER:
                return new IsolatedClassLoaderWorkerRequirement(daemonForkOptions.getJavaForkOptions().getWorkingDir(), daemonForkOptions.getClassLoaderStructure());
            case PROCESS:
                return new ForkedWorkerRequirement(daemonForkOptions.getJavaForkOptions().getWorkingDir(), daemonForkOptions);
            default:
                throw new IllegalArgumentException("Received worker with unsupported isolation mode: " + isolationMode);
        }
    }

    protected abstract DaemonForkOptions toDaemonForkOptions(T spec);

    protected abstract CompilerParameters getCompilerParameters(T spec);

    protected BaseForkOptions mergeForkOptions(BaseForkOptions left, BaseForkOptions right) {
        BaseForkOptions merged = new BaseForkOptions();
        merged.setMemoryInitialSize(mergeHeapSize(left.getMemoryInitialSize(), right.getMemoryInitialSize()));
        merged.setMemoryMaximumSize(mergeHeapSize(left.getMemoryMaximumSize(), right.getMemoryMaximumSize()));
        Set<String> mergedJvmArgs = normalized(left.getJvmArgs());
        mergedJvmArgs.addAll(normalized(right.getJvmArgs()));
        merged.setJvmArgs(Lists.newArrayList(mergedJvmArgs));
        return merged;
    }

    public abstract static class CompilerParameters implements WorkParameters, Serializable {
        private final String compilerClassName;
        private final Object[] compilerInstanceParameters;

        public CompilerParameters(String compilerClassName, Object[] compilerInstanceParameters) {
            this.compilerClassName = compilerClassName;
            this.compilerInstanceParameters = compilerInstanceParameters;
        }

        public String getCompilerClassName() {
            return compilerClassName;
        }

        public Object[] getCompilerInstanceParameters() {
            return compilerInstanceParameters;
        }

        abstract public CompileSpec getCompileSpec();
    }

    public static class CompilerWorkAction implements WorkAction<CompilerParameters>, ProvidesWorkResult {
        private DefaultWorkResult workResult;
        private final CompilerParameters parameters;
        private final Instantiator instantiator;

        @Inject
        public CompilerWorkAction(CompilerParameters parameters, Instantiator instantiator) {
            this.parameters = parameters;
            this.instantiator = instantiator;
        }

        @Override
        public CompilerParameters getParameters() {
            return parameters;
        }

        @Override
        public void execute() {
            Class<? extends Compiler<?>> compilerClass = Cast.uncheckedCast(ClassLoaderUtils.classFromContextLoader(getParameters().getCompilerClassName()));
            Compiler<?> compiler = instantiator.newInstance(compilerClass, getParameters().getCompilerInstanceParameters());
            setWorkResult(compiler.execute(Cast.uncheckedCast(getParameters().getCompileSpec())));
        }

        private void setWorkResult(WorkResult workResult) {
            if (workResult instanceof DefaultWorkResult) {
                this.workResult = (DefaultWorkResult) workResult;
            } else {
                this.workResult = new DefaultWorkResult(workResult.getDidWork(), null);
            }
        }

        @Override
        public DefaultWorkResult getWorkResult() {
            return workResult;
        }
    }
}
