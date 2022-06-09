package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.manage.schema.extract.NodeInitializerExtractionStrategy;

public interface NodeInitializerRegistry {
    NodeInitializer getNodeInitializer(NodeInitializerContext<?> nodeInitializerContext);

    void ensureHasInitializer(NodeInitializerContext<?> nodeInitializer);

    void registerStrategy(NodeInitializerExtractionStrategy strategy);
}
