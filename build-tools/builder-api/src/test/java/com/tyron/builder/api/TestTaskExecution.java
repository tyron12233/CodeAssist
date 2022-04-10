package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

import java.util.List;

public class TestTaskExecution extends TestTaskExecutionCase {

    @Override
    public void evaluateProject(BuildProject project) {
        TaskContainer tasks = project.getTasks();
        tasks.register("MyTask", task -> {
            task.doLast(__ -> {
                System.out.println("Running " + task.getName());
            });
        });
    }

    @Override
    public List<String> getTasksToExecute() {
        return ImmutableList.of("MyTask");
    }

//    @Test
//    public void testSkipOnlyIf() {
//        MutableBoolean executed = new MutableBoolean(false);
//        Action<BuildProject> evaluationAction = new Action<BuildProject>() {
//            @Override
//            public void execute(BuildProject project) {
//                TaskContainer tasks = project.getTasks();
//                tasks.register("SkipTask", new Action<Task>() {
//                    @Override
//                    public void execute(Task task) {
//                        task.onlyIf(new Predicate<Task>() {
//                            @Override
//                            public boolean test(Task t) {
//                                return false;
//                            }
//                        });
//                        task.doLast(new Action<Task>() {
//                            @Override
//                            public void execute(Task t) {
//                                executed.set(true);
//                            }
//                        });
//                    }
//                });
//            }
//        };
//
//        evaluateProject(project, evaluationAction);
//        executeProject(project, "SkipTask");
//
//        assert !executed.get();
//    }

}
