package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.component.SoftwareComponentVariant;

public interface UsageContext extends SoftwareComponentVariant {
    @Deprecated
    Usage getUsage(); // kept for backwards compatibility of plugins (like kotlin-multiplatform) using internal APIs
}
