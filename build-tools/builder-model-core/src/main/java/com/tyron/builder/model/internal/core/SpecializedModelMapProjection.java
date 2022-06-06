package com.tyron.builder.model.internal.core;

import com.google.common.base.Optional;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import javax.annotation.Nullable;
import java.util.Collections;

/**
 * Should be used along with {@code PolymorphicModelMapProjection}.
 */
public class SpecializedModelMapProjection<P, E> implements ModelProjection {
    private final ModelType<P> publicType;
    private final ModelType<E> elementType;

    private final Class<? extends P> viewImpl;
    private final ChildNodeInitializerStrategyAccessor<? super E> creatorStrategyAccessor;

    public SpecializedModelMapProjection(ModelType<P> publicType, ModelType<E> elementType, Class<? extends P> viewImpl, ChildNodeInitializerStrategyAccessor<? super E> creatorStrategyAccessor) {
        this.publicType = publicType;
        this.elementType = elementType;
        this.viewImpl = viewImpl;
        this.creatorStrategyAccessor = creatorStrategyAccessor;
    }

    @Override
    public Iterable<String> getTypeDescriptions(MutableModelNode node) {
        return Collections.singleton(publicType.toString());
    }

    @Nullable
    @Override
    public <T> ModelView<? extends T> asImmutable(ModelType<T> type, MutableModelNode node, @Nullable ModelRuleDescriptor ruleDescriptor) {
        if (canBeViewedAs(type)) {
            return Cast.uncheckedCast(toView(node, ruleDescriptor, false));
        } else {
            return null;
        }
    }

    @Nullable
    @Override
    public <T> ModelView<? extends T> asMutable(ModelType<T> type, MutableModelNode node, ModelRuleDescriptor ruleDescriptor) {
        if (canBeViewedAs(type)) {
            return Cast.uncheckedCast(toView(node, ruleDescriptor, true));
        } else {
            return null;
        }
    }

    private ModelView<P> toView(MutableModelNode modelNode, ModelRuleDescriptor ruleDescriptor, boolean mutable) {
        ChildNodeInitializerStrategy<? super E> creatorStrategy = creatorStrategyAccessor.getStrategy(modelNode);
        DefaultModelViewState state = new DefaultModelViewState(modelNode.getPath(), publicType, ruleDescriptor, mutable, !mutable);
        P instance = DirectInstantiator.instantiate(viewImpl, publicType, elementType, ruleDescriptor, modelNode, state, creatorStrategy);
        return InstanceModelView.of(modelNode.getPath(), publicType, instance, state.closer());
    }

    @Override
    public <T> boolean canBeViewedAs(ModelType<T> targetType) {
        return targetType.isAssignableFrom(publicType);
    }

    @Override
    public Optional<String> getValueDescription(MutableModelNode modelNodeInternal) {
        return Optional.absent();
    }
}
