package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.plan.DefaultExecutionPlan;
import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchy;
import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.DefaultLease;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
    private final DefaultResourceLockCoordinationService resourceLockService;
    private final DefaultWorkerLeaseService defaultWorkerLeaseService;

    public TaskExecutor(ProjectInternal project) {
        this.project = project;
        this.taskNodeFactory = new TaskNodeFactory(new DefaultNodeValidator());
        this.taskDependencyResolver = new TaskDependencyResolver(
                Collections.singletonList(new TaskNodeDependencyResolver(taskNodeFactory)));
        this.executionNodeAccessHierarchy = new ExecutionNodeAccessHierarchy(CaseSensitivity.CASE_INSENSITIVE, DEFAULT_STAT);
        this.resourceLockService = new DefaultResourceLockCoordinationService();
        this.defaultWorkerLeaseService = new DefaultWorkerLeaseService(resourceLockService);
    }

    public void execute(String... paths) {
        Task[] tasks = new Task[paths.length];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = project.getTaskContainer().resolveTask(paths[i]);
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

        BuildCancellationToken cancellationToken = new BuildCancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            @Override
            public void cancel() {

            }

            @Override
            public boolean addCallback(Runnable cancellationHandler) {
                return false;
            }

            @Override
            public void removeCallback(Runnable cancellationHandler) {

            }
        };
        DefaultPlanExecutor executor = new DefaultPlanExecutor(
                new DefaultExecutorFactory(),
                defaultWorkerLeaseService,
                cancellationToken,
                resourceLockService
        );
        DefaultTaskExecutionGraph graph = new DefaultTaskExecutionGraph(executor, Collections
                .singletonList((node, context) -> {
                    if (node instanceof LocalTaskNode) {
                        LocalTaskNode localTaskNode = (LocalTaskNode) node;
                        localTaskNode.getTask().getActions().forEach(action -> {
                            action.execute(localTaskNode.getTask());
                        });
                        System.out.println("    Executing node: " + node);
                    }
                    return true;
                }));
        graph.populate(executionPlan);
        List<Throwable> failures = new ArrayList<>();
        resourceLockService.withStateLock(() -> {
            DefaultLease lease = defaultWorkerLeaseService.getWorkerLease();
            lease.tryLock();
            graph.execute(executionPlan, failures);
        });
    }
}
