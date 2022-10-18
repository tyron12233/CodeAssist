package org.gradle.api.internal.component;

import org.gradle.api.attributes.Usage;
import org.gradle.api.component.SoftwareComponentVariant;

public interface UsageContext extends SoftwareComponentVariant {
    @Deprecated
    Usage getUsage(); // kept for backwards compatibility of plugins (like kotlin-multiplatform) using internal APIs
}
