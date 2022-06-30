/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.internal.java.usagecontext;

import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationVariant;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.component.IvyPublishingAwareContext;
import com.tyron.builder.api.internal.component.MavenPublishingAwareContext;
import com.tyron.builder.api.plugins.internal.AbstractConfigurationUsageContext;

public class FeatureConfigurationUsageContext extends AbstractConfigurationUsageContext implements MavenPublishingAwareContext, IvyPublishingAwareContext {
    private final Configuration configuration;
    private final ScopeMapping scopeMapping;
    private final boolean optional;

    public FeatureConfigurationUsageContext(String name, Configuration configuration, ConfigurationVariant variant, String mavenScope, boolean optional) {
        super(name, ((AttributeContainerInternal)variant.getAttributes()).asImmutable(), variant.getArtifacts());
        this.configuration = configuration;
        this.scopeMapping = ScopeMapping.of(mavenScope, optional);
        this.optional = optional;
    }

    @Override
    protected Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public ScopeMapping getScopeMapping() {
        return scopeMapping;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }
}
