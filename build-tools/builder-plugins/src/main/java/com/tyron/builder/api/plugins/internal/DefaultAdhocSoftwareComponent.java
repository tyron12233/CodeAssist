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
package com.tyron.builder.api.plugins.internal;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.component.AdhocComponentWithVariants;
import com.tyron.builder.api.component.ConfigurationVariantDetails;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.component.SoftwareComponentInternal;
import com.tyron.builder.api.internal.component.UsageContext;
import com.tyron.builder.api.internal.java.usagecontext.ConfigurationVariantMapping;
import com.tyron.builder.internal.reflect.Instantiator;

import java.util.Map;
import java.util.Set;

public class DefaultAdhocSoftwareComponent implements AdhocComponentWithVariants, SoftwareComponentInternal {
    private final String componentName;
    private final Map<Configuration, ConfigurationVariantMapping> variants = Maps.newLinkedHashMapWithExpectedSize(4);
    private final Instantiator instantiator;

    public DefaultAdhocSoftwareComponent(String componentName, Instantiator instantiator) {
        this.componentName = componentName;
        this.instantiator = instantiator;
    }

    @Override
    public String getName() {
        return componentName;
    }

    @Override
    public void addVariantsFromConfiguration(Configuration outgoingConfiguration, Action<? super ConfigurationVariantDetails> spec) {
        variants.put(outgoingConfiguration, new ConfigurationVariantMapping((ConfigurationInternal) outgoingConfiguration, spec, instantiator));
    }

    @Override
    public void withVariantsFromConfiguration(Configuration outgoingConfiguration, Action<? super ConfigurationVariantDetails> action) {
        if (!variants.containsKey(outgoingConfiguration)) {
            throw new InvalidUserDataException("Variant for configuration " + outgoingConfiguration.getName() + " does not exist in component " + componentName);
        }
        variants.get(outgoingConfiguration).addAction(action);
    }

    @Override
    public Set<? extends UsageContext> getUsages() {
        ImmutableSet.Builder<UsageContext> builder = new ImmutableSet.Builder<>();
        for (ConfigurationVariantMapping variant : variants.values()) {
            variant.collectUsageContexts(builder);
        }
        return builder.build();
    }
}
