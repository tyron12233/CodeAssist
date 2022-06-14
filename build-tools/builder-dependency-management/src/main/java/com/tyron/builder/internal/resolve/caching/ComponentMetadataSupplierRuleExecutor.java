/*
 * Copyright 2018 the original author or authors.
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
package com.tyron.builder.internal.resolve.caching;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.artifacts.ComponentMetadata;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplierDetails;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ResolvedModuleVersion;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

import java.time.Duration;

public class ComponentMetadataSupplierRuleExecutor extends CrossBuildCachingRuleExecutor<ModuleVersionIdentifier, ComponentMetadataSupplierDetails, ComponentMetadata> {
    private final static Transformer<String, ModuleVersionIdentifier> KEY_TO_SNAPSHOTTABLE = Object::toString;

    public ComponentMetadataSupplierRuleExecutor(GlobalScopedCache globalScopedCache,
                                                 InMemoryCacheDecoratorFactory cacheDecoratorFactory,
                                                 ValueSnapshotter snapshotter,
                                                 BuildCommencedTimeProvider timeProvider,
                                                 Serializer<ComponentMetadata> componentMetadataSerializer) {
        super("md-supplier", globalScopedCache, cacheDecoratorFactory, snapshotter, timeProvider, createValidator(timeProvider), KEY_TO_SNAPSHOTTABLE, componentMetadataSerializer);
    }

    public static EntryValidator<ComponentMetadata> createValidator(final BuildCommencedTimeProvider timeProvider) {
        return (policy, entry) -> {
            Duration age = Duration.ofMillis(timeProvider.getCurrentTime() - entry.getTimestamp());
            final ComponentMetadata result = entry.getResult();
            return !policy.moduleExpiry(new SimpleResolvedModuleVersion(result), age, result.isChanging()).isMustCheck();
        };
    }

    private static class SimpleResolvedModuleVersion implements ResolvedModuleVersion {
        private final ComponentMetadata result;

        public SimpleResolvedModuleVersion(ComponentMetadata result) {
            this.result = result;
        }

        @Override
        public ModuleVersionIdentifier getId() {
            return result.getId();
        }
    }
}
