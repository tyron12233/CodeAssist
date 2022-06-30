package com.tyron.builder.workers.internal;

import com.tyron.builder.workers.WorkParameters;

/**
 * This is used to bridge between the "old" worker api with untyped parameters and the typed
 * parameter api.  It allows us to maintain backwards compatibility at the api layer, but use
 * only typed parameters under the covers.  This can be removed once the old api is retired.
 */
public interface AdapterWorkParameters extends WorkParameters {
    void setImplementationClassName(String implementationClassName);
    String getImplementationClassName();

    void setParams(Object[] params);
    Object[] getParams();

    void setDisplayName(String displayName);
    String getDisplayName();
}
