package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskSelectionException;
import com.tyron.builder.api.execution.plan.DefaultExecutionPlan;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchy;
import com.tyron.builder.api.execution.plan.LocalTaskNodeExecutor;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.DefaultLease;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;
import com.tyron.builder.api.internal.work.WorkerLeaseRegistry;
import com.tyron.builder.api.internal.work.WorkerLeaseService;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TaskExecutor {

    private static final Stat DEFAULT_STAT =new Stat() {
        @Override
        public int getUnixMode(File f) throws FileException {
            return 0;
        }

        @Override
        public FileMetadata stat(File f) throws FileException {
            return new FileMetadata() {
                @Override
                public FileType getType() {
                    if (!f.exists()) {
                        return FileType.Missing;
                    }
                    if (f.isDirectory()) {
                        return FileType.Directory;
                    }
                    return FileType.RegularFile;
                }

                @Override
                public long getLastModified() {
                    return f.lastModified();
                }

                @Override
                public long getLength() {
                    return f.length();
                }

                @Override
                public AccessType getAccessType() {
                    if (f.isAbsolute()) {
                        return AccessType.DIRECT;
                    }
                    return AccessType.VIA_SYMLINK;
                }
            };
        }
    };

    private final ProjectInternal project;
    private final TaskNodeFactory taskNodeFactory;
    private final TaskDependencyResolver taskDependencyResolver;
    private final  ExecutionNodeAccessHierarchy executionNodeAccessHierarchy;
    private final ResourceLockCoordinationService resourceLockService;
    private final WorkerLeaseService defaultWorkerLeaseService;

    public TaskExecutor(ProjectInternal project) {
        this.project = project;
        ServiceRegistry services = project.getGradle().getServices();
        this.taskNodeFactory = services.get(TaskNodeFactory.class);
        this.taskDependencyResolver = services.get(TaskDependencyResolver.class);
        this.executionNodeAccessHierarchy = new ExecutionNodeAccessHierarchy(CaseSensitivity.CASE_INSENSITIVE, DEFAULT_STAT);
        this.resourceLockService = services.get(ResourceLockCoordinationService.class);
        this.defaultWorkerLeaseService = services.get(WorkerLeaseService.class);
    }

    public void execute(String... paths) {
        Task[] tasks = new Task[paths.length];
        for (int i = 0; i < tasks.length; i++) {
            Task task = project.getTasks().resolveTask(paths[i]);

            if (task == null) {
                throw new TaskSelectionException("Task '" + paths[i] + "' not found in project '" + project.getPath() + "'");
            }
            tasks[i] = task;
        }
        execute(tasks);
    }

    public void execute(Task... tasks) {
        DefaultExecutionPlan executionPlan = new DefaultExecutionPlan(
                "myPlan",
                taskNodeFactory,
                resourceLockService,
                taskDependencyResolver,
                executionNodeAccessHierarchy,
                executionNodeAccessHierarchy
        );
        executionPlan.addEntryTasks(Arrays.asList(tasks));
        executionPlan.determineExecutionPlan();

        TaskExecutionGraphInternal taskGraph = project.getGradle().getTaskGraph();
        taskGraph.populate(executionPlan);

        List<Throwable> failures = new ArrayList<>();
        resourceLockService.withStateLock(() -> {
            WorkerLeaseRegistry.WorkerLease lease = defaultWorkerLeaseService.getWorkerLease();
            lease.tryLock();
            taskGraph.execute(executionPlan, failures);
        });
    }
}
