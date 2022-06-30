package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface ArtifactTransformListener {

    /**
     * This method is called immediately before a transformer is invoked.
     */
    void beforeTransformerInvocation(Describable transformer, Describable subject);

    /**
     * This method is call immediately after a transformer has been invoked.
     */
    void afterTransformerInvocation(Describable transformer, Describable subject);
}
