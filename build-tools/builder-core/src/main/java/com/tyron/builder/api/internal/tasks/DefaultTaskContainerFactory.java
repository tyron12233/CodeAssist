package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.project.CrossProjectConfigurator;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.ITaskFactory;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.internal.BiAction;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.model.RuleBasedPluginListener;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.model.collection.internal.BridgedCollections;
import com.tyron.builder.model.internal.core.ChildNodeInitializerStrategyAccessors;
import com.tyron.builder.model.internal.core.DirectNodeNoInputsModelAction;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelMapModelProjection;
import com.tyron.builder.model.internal.core.ModelNode;
import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.ModelRegistrations;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.model.internal.core.NodeBackedModelMap;
import com.tyron.builder.model.internal.core.UnmanagedModelProjection;
import com.tyron.builder.model.internal.core.rule.describe.SimpleModelRuleDescriptor;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.model.internal.type.ModelType;

import static com.tyron.builder.model.internal.core.NodePredicate.allLinks;

public class DefaultTaskContainerFactory implements Factory<TaskContainerInternal> {
    private static final ModelType<DefaultTaskContainer> DEFAULT_TASK_CONTAINER_MODEL_TYPE = ModelType.of(DefaultTaskContainer.class);
    private static final ModelType<TaskContainer> TASK_CONTAINER_MODEL_TYPE = ModelType.of(TaskContainer.class);
    private static final ModelType<Task> TASK_MODEL_TYPE = ModelType.of(Task.class);
    private static final ModelReference<Task> TASK_MODEL_REFERENCE = ModelReference.of(TASK_MODEL_TYPE);
    private static final SimpleModelRuleDescriptor COPY_TO_TASK_CONTAINER_DESCRIPTOR = new SimpleModelRuleDescriptor("copyToTaskContainer");
    private final Instantiator instantiator;
    private final ITaskFactory taskFactory;
    private final CollectionCallbackActionDecorator callbackDecorator;
    private final ProjectInternal project;
    private final TaskStatistics statistics;
    private final BuildOperationExecutor buildOperationExecutor;
    private final CrossProjectConfigurator crossProjectConfigurator;

    public DefaultTaskContainerFactory(Instantiator instantiator,
                                       ITaskFactory taskFactory,
                                       ProjectInternal project,
                                       TaskStatistics statistics,
                                       BuildOperationExecutor buildOperationExecutor,
                                       CrossProjectConfigurator crossProjectConfigurator,
                                       CollectionCallbackActionDecorator callbackDecorator) {
        this.instantiator = instantiator;
        this.taskFactory = taskFactory;
        this.project = project;
        this.statistics = statistics;
        this.buildOperationExecutor = buildOperationExecutor;
        this.crossProjectConfigurator = crossProjectConfigurator;
        this.callbackDecorator = callbackDecorator;
    }

    @Override
    public TaskContainerInternal create() {
        DefaultTaskContainer tasks = instantiator.newInstance(DefaultTaskContainer.class, project, instantiator, taskFactory, statistics, buildOperationExecutor, crossProjectConfigurator, callbackDecorator);
        bridgeIntoSoftwareModelWhenNeeded(tasks);
        return tasks;
    }

    private void bridgeIntoSoftwareModelWhenNeeded(final DefaultTaskContainer tasks) {
        project.addRuleBasedPluginListener(project -> {
            ModelReference<DefaultTaskContainer> containerReference = ModelReference.of(TaskContainerInternal.MODEL_PATH, DEFAULT_TASK_CONTAINER_MODEL_TYPE);

            ModelRegistrations.Builder registrationBuilder = BridgedCollections.bridgeTaskCollection(
                containerReference, mutableModelNode -> {
                    tasks.setModelNode(mutableModelNode);
                    return tasks;
                },
                new Task.Namer(),
                "Project.<init>.tasks()",
                new Namer()
            );

            ModelRegistry modelRegistry = ((ProjectInternal) project).getServices().get(ModelRegistry.class);
            modelRegistry.register(
                registrationBuilder
                    .withProjection(ModelMapModelProjection.unmanaged(TASK_MODEL_TYPE, ChildNodeInitializerStrategyAccessors.of(NodeBackedModelMap.createUsingParentNode(new Transformer<NamedEntityInstantiator<Task>, MutableModelNode>() {
                        @Override
                        public NamedEntityInstantiator<Task> transform(MutableModelNode modelNode) {
                            return modelNode.getPrivateData(DEFAULT_TASK_CONTAINER_MODEL_TYPE).getEntityInstantiator();
                        }
                    }))))
                    .withProjection(UnmanagedModelProjection.of(TASK_CONTAINER_MODEL_TYPE))
                    .build()
            );

            ModelNode modelNode = modelRegistry.atStateOrLater(TaskContainerInternal.MODEL_PATH, ModelNode.State.Created);

            // TODO LD use something more stable than a cast here
            MutableModelNode mutableModelNode = (MutableModelNode) modelNode;

            // Add tasks created through rules to the actual task container
            mutableModelNode.applyTo(allLinks(), ModelActionRole.Initialize, DirectNodeNoInputsModelAction.of(TASK_MODEL_REFERENCE, COPY_TO_TASK_CONTAINER_DESCRIPTOR, new BiAction<MutableModelNode, Task>() {
                @Override
                public void execute(MutableModelNode modelNode, Task task) {
                    TaskContainerInternal taskContainer = modelNode.getParent().getPrivateData(TaskContainerInternal.MODEL_TYPE);
                    taskContainer.addInternal(task);
                }
            }));
        });
    }

    private static class Namer implements Transformer<String, String> {
        @Override
        public String transform(String s) {
            return "Project.<init>.tasks." + s + "()";
        }
    }

}
