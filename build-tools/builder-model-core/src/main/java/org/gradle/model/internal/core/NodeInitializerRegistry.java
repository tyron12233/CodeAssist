package org.gradle.model.internal.core;

import org.gradle.model.internal.manage.schema.extract.NodeInitializerExtractionStrategy;

public interface NodeInitializerRegistry {
    NodeInitializer getNodeInitializer(NodeInitializerContext<?> nodeInitializerContext);

    void ensureHasInitializer(NodeInitializerContext<?> nodeInitializer);

    void registerStrategy(NodeInitializerExtractionStrategy strategy);
}
