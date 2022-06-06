package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.work.FileChange;
import com.tyron.builder.work.InputChanges;

import java.lang.reflect.Method;

public class BridgingIncrementalInputsTaskAction extends IncrementalInputsTaskAction {
    public BridgingIncrementalInputsTaskAction(Class<? extends Task> taskType, Method method) {
        super(taskType, method);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void doExecute(Task task, String methodName) {
        JavaMethod.of(task, Object.class, methodName, com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs.class).invoke(task, new BridgingInputChanges(getInputChanges()));
    }

    @SuppressWarnings("deprecation")
    private static class BridgingInputChanges implements com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs, InputChanges {
        private final InputChanges inputChanges;

        public BridgingInputChanges(InputChanges inputChanges) {
            this.inputChanges = inputChanges;
        }

        @Override
        public boolean isIncremental() {
            return inputChanges.isIncremental();
        }

        @Override
        public Iterable<FileChange> getFileChanges(FileCollection parameter) {
            return inputChanges.getFileChanges(parameter);
        }

        @Override
        public Iterable<FileChange> getFileChanges(Provider<? extends FileSystemLocation> parameter) {
            return inputChanges.getFileChanges(parameter);
        }

        @Override
        public void outOfDate(Action<? super InputFileDetails> outOfDateAction) {
            throw new UnsupportedOperationException("Only for bridging");
        }

        @Override
        public void removed(Action<? super InputFileDetails> removedAction) {
            throw new UnsupportedOperationException("Only for bridging");
        }
    }
}
