package com.tyron.builder.model.internal.core;

import com.tyron.builder.internal.BiAction;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.internal.registry.RuleContext;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.model.internal.type.ModelTypes;

public class ModelMaps {

    private static final ModelReference<NodeInitializerRegistry> NODE_INITIALIZER_REGISTRY_MODEL_REFERENCE = ModelReference.of(NodeInitializerRegistry.class);
    private static final ModelType<ChildNodeInitializerStrategy<?>> CHILD_NODE_INITIALIZER_STRATEGY_MODEL_TYPE =
        Cast.uncheckedCast(ModelType.of(ChildNodeInitializerStrategy.class));

    public static <T> MutableModelNode addModelMapNode(MutableModelNode modelNode, ModelType<T> elementModelType, String name) {
        modelNode.addLink(
            ModelRegistrations.of(modelNode.getPath().child(name))
                .action(ModelActionRole.Create, NODE_INITIALIZER_REGISTRY_MODEL_REFERENCE, new BiAction<MutableModelNode, NodeInitializerRegistry>() {
                    @Override
                    public void execute(MutableModelNode node, NodeInitializerRegistry nodeInitializerRegistry) {
                        ChildNodeInitializerStrategy<T> childFactory =
                            NodeBackedModelMap.createUsingRegistry(nodeInitializerRegistry);
                        node.setPrivateData(CHILD_NODE_INITIALIZER_STRATEGY_MODEL_TYPE, childFactory);
                    }
                })
                .descriptor(modelNode.getDescriptor())
                .withProjection(
                    ModelMapModelProjection.unmanaged(elementModelType, ChildNodeInitializerStrategyAccessors.fromPrivateData())
                )
                .build()
        );
        MutableModelNode mapNode = modelNode.getLink(name);
        assert mapNode != null;
        return mapNode;
    }

    public static <T> ModelMap<T> toView(MutableModelNode mapNode, ModelType<T> elementModelType) {
        mapNode.ensureUsable();
        return mapNode.asMutable(
                ModelTypes.modelMap(elementModelType),
                RuleContext.get()
        ).getInstance();
    }
}
