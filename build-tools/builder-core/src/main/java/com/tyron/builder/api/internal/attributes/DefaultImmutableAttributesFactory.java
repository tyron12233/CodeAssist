package com.tyron.builder.api.internal.attributes;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.internal.snapshot.impl.CoercingStringValueSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ServiceScope(Scopes.BuildSession.class)
public class DefaultImmutableAttributesFactory implements ImmutableAttributesFactory {
    private final ImmutableAttributes root;
    private final Map<ImmutableAttributes, List<DefaultImmutableAttributes>> children;
    private final IsolatableFactory isolatableFactory;
    private final UsageCompatibilityHandler usageCompatibilityHandler;
    private NamedObjectInstantiator instantiator;

    public DefaultImmutableAttributesFactory(IsolatableFactory isolatableFactory, NamedObjectInstantiator instantiator) {
        this.isolatableFactory = isolatableFactory;
        this.instantiator = instantiator;
        this.root = ImmutableAttributes.EMPTY;
        this.children = Maps.newHashMap();
        children.put(root, new ArrayList<DefaultImmutableAttributes>());
        usageCompatibilityHandler = new UsageCompatibilityHandler(isolatableFactory, instantiator);
    }

    public int size() {
        return children.size();
    }

    @Override
    public AttributeContainerInternal mutable() {
        return new DefaultMutableAttributeContainer(this);
    }

    @Override
    public AttributeContainerInternal mutable(AttributeContainerInternal parent) {
        return new DefaultMutableAttributeContainer(this, parent);
    }

    @Override
    public <T> ImmutableAttributes of(Attribute<T> key, T value) {
        return concat(root, key, value);
    }

    @Override
    public <T> ImmutableAttributes concat(ImmutableAttributes node, Attribute<T> key, @Nullable T value) {
        return concat(node, key, isolate(value));
    }

    private <T> Isolatable<T> isolate(@Nullable T value) {
        if (value instanceof String) {
            return Cast.uncheckedNonnullCast(new CoercingStringValueSnapshot((String) value, instantiator));
        } else {
            return isolatableFactory.isolate(value);
        }
    }

    @Override
    public <T> ImmutableAttributes concat(ImmutableAttributes node, Attribute<T> key, Isolatable<T> value) {
        if (key.equals(Usage.USAGE_ATTRIBUTE) || key.getName().equals(Usage.USAGE_ATTRIBUTE.getName())) {
            return usageCompatibilityHandler.doConcat(this, node, key, value);
        } else {
            return doConcatIsolatable(node, key, value);
        }
    }

    ImmutableAttributes doConcatIsolatable(ImmutableAttributes node, Attribute<?> key, @Nullable Isolatable<?> value) {
        synchronized (this) {
            List<DefaultImmutableAttributes> nodeChildren = children.computeIfAbsent(node, k -> Lists.newArrayList());
            for (DefaultImmutableAttributes child : nodeChildren) {
                if (child.attribute.equals(key) && child.value.equals(value)) {
                    return child;
                }
            }
            DefaultImmutableAttributes child = new DefaultImmutableAttributes((DefaultImmutableAttributes) node, key, value);
            nodeChildren.add(child);
            return child;
        }
    }

    public ImmutableAttributes getRoot() {
        return root;
    }

    @Override
    public ImmutableAttributes concat(ImmutableAttributes attributes1, ImmutableAttributes attributes2) {
        if (attributes1 == ImmutableAttributes.EMPTY) {
            return attributes2;
        }
        if (attributes2 == ImmutableAttributes.EMPTY) {
            return attributes1;
        }
        ImmutableAttributes current = attributes2;
        for (Attribute<?> attribute : attributes1.keySet()) {
            if (!current.findEntry(attribute.getName()).isPresent()) {
                if (attributes1 instanceof DefaultImmutableAttributes) {
                    current = doConcatIsolatable(current, attribute, ((DefaultImmutableAttributes) attributes1).getIsolatableAttribute(attribute));
                } else {
                    current = concat(current, Cast.uncheckedNonnullCast(attribute), attributes1.getAttribute(attribute));
                }
            }
        }
        return current;
    }

    @Override
    public ImmutableAttributes safeConcat(ImmutableAttributes attributes1, ImmutableAttributes attributes2) throws AttributeMergingException {
        if (attributes1 == ImmutableAttributes.EMPTY) {
            return attributes2;
        }
        if (attributes2 == ImmutableAttributes.EMPTY) {
            return attributes1;
        }
        ImmutableAttributes current = attributes2;
        for (Attribute<?> attribute : attributes1.keySet()) {
            AttributeValue<?> entry = current.findEntry(attribute.getName());
            if (entry.isPresent()) {
                Object currentAttribute = entry.get();
                Object existingAttribute = attributes1.getAttribute(attribute);
                if (!currentAttribute.equals(existingAttribute)) {
                    throw new AttributeMergingException(attribute, existingAttribute, currentAttribute);
                }
            }
            if (attributes1 instanceof DefaultImmutableAttributes) {
                current = doConcatIsolatable(current, attribute, ((DefaultImmutableAttributes) attributes1).getIsolatableAttribute(attribute));
            } else {
                current = concat(current, Cast.uncheckedNonnullCast(attribute), attributes1.getAttribute(attribute));
            }
        }
        return current;
    }
}
