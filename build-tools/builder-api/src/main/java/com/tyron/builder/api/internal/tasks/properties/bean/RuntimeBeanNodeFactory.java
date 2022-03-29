package com.tyron.builder.api.internal.tasks.properties.bean;


import com.tyron.builder.api.internal.tasks.properties.TypeMetadata;
import com.tyron.builder.api.internal.tasks.properties.TypeMetadataStore;

import java.util.Map;

public class RuntimeBeanNodeFactory {

    private final TypeMetadataStore metadataStore;

    public RuntimeBeanNodeFactory(TypeMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    public RuntimeBeanNode<?> createRoot(Object bean) {
        return null;
//        return new RootRuntimeBeanNode(bean, metadataStore.getTypeMetadata(bean.getClass()));
    }

    public RuntimeBeanNode<?> create(RuntimeBeanNode parentNode, String propertyName, Object bean) {
        parentNode.checkCycles(propertyName, bean);
        TypeMetadata typeMetadata = metadataStore.getTypeMetadata(bean.getClass());
//        if (!typeMetadata.hasAnnotatedProperties()) {
//            if (bean instanceof Map<?, ?>) {
//                return new MapRuntimeBeanNode(parentNode, propertyName, (Map<?, ?>) bean, typeMetadata);
//            }
//            if (bean instanceof Iterable<?>) {
//                return new IterableRuntimeBeanNode(parentNode, propertyName, (Iterable<?>) bean, typeMetadata);
//            }
//        }
//        return new NestedRuntimeBeanNode(parentNode, propertyName, bean, typeMetadata);
        return null;
    }
}
