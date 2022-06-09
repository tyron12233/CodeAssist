package com.tyron.builder.model.collection.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NamedDomainObjectCollection;
import com.tyron.builder.api.Namer;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectCollection;
import com.tyron.builder.api.internal.tasks.DefaultTaskCollection;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelPath;
import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.ModelRegistration;
import com.tyron.builder.model.internal.core.ModelRegistrations;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.rule.describe.SimpleModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

public abstract class BridgedCollections {

    private BridgedCollections() {
    }

    public static <I extends Task, C extends DefaultTaskCollection<I>> ModelRegistrations.Builder bridgeTaskCollection(
        final ModelReference<C> containerReference,
        final Transformer<? extends C, ? super MutableModelNode> containerFactory,
        final Namer<? super I> namer,
        String descriptor,
        final Transformer<String, String> itemDescriptorGenerator
    ) {
        final ModelPath containerPath = containerReference.getPath();
        final ModelType<C> containerType = containerReference.getType();
        assert containerPath != null : "container reference path cannot be null";

        return ModelRegistrations.of(containerPath)
            .action(ModelActionRole.Create, new Action<MutableModelNode>() {
                @Override
                public void execute(final MutableModelNode containerNode) {
                    final C container = containerFactory.transform(containerNode);
                    containerNode.setPrivateData(containerType, container);
                }
            })
            .action(ModelActionRole.Create, new Action<MutableModelNode>() {
                @Override
                public void execute(final MutableModelNode containerNode) {
                    final C container = containerNode.getPrivateData(containerType);
                    container.whenElementKnown(new Action<DefaultNamedDomainObjectCollection.ElementInfo<I>>() {
                        @Override
                        public void execute(DefaultNamedDomainObjectCollection.ElementInfo<I> info) {
                            final String name = info.getName();
                            if (!containerNode.isMutable()) {
                                // Ignore tasks created after not closed
                                return;
                            }
                            if (!containerNode.hasLink(name)) {
                                ModelRegistration itemRegistration = ModelRegistrations
                                    .unmanagedInstanceOf(
                                        ModelReference.of(containerPath.child(name), Cast.<Class<I>>uncheckedNonnullCast(info.getType())),
                                        new ExtractFromParentContainer<>(name, containerType)
                                    )
                                    .descriptor(new SimpleModelRuleDescriptor(new Factory<String>() {
                                        @Override
                                        public String create() {
                                            return itemDescriptorGenerator.transform(name);
                                        }
                                    }))
                                    .build();
                                containerNode.addLink(itemRegistration);
                            }
                        }
                    });
                    container.whenObjectRemovedInternal(new Action<I>() {
                        @Override
                        public void execute(I item) {
                            String name = namer.determineName(item);
                            containerNode.removeLink(name);
                        }
                    });
                }
            })
            .descriptor(descriptor);
    }

    private static class ExtractFromParentContainer<I, C extends NamedDomainObjectCollection<I>> implements Transformer<I, MutableModelNode> {

        private final String name;
        private final ModelType<C> containerType;

        public ExtractFromParentContainer(String name, ModelType<C> containerType) {
            this.name = name;
            this.containerType = containerType;
        }

        @Override
        public I transform(MutableModelNode modelNode) {
            return modelNode.getParent().getPrivateData(containerType).getByName(name);
        }
    }

}

