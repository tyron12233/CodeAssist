package com.tyron.builder.api.internal.component;

public interface MavenPublishingAwareContext extends UsageContext {
    ScopeMapping getScopeMapping();

    // Order is important!
    enum ScopeMapping {
        compile,
        runtime,
        compile_optional,
        runtime_optional;

        public static ScopeMapping of(String scope, boolean optional) {
            if (optional) {
                scope += "_optional";
            }
            return ScopeMapping.valueOf(scope);
        }
    }
}
