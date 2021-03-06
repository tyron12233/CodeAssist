/*
 * Copyright 2011 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache;

import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;

class ModuleMetadataCacheEntry {
    static final byte TYPE_MISSING = 0;
    static final byte TYPE_PRESENT = 1;

    final byte type;
    final boolean isChanging;
    final long createTimestamp;

    ModuleMetadataCacheEntry(byte type, boolean isChanging, long createTimestamp) {
        this.type = type;
        this.isChanging = isChanging;
        this.createTimestamp = createTimestamp;
    }

    public static ModuleMetadataCacheEntry forMissingModule(long createTimestamp) {
        return new MissingModuleCacheEntry(createTimestamp);
    }

    public static ModuleMetadataCacheEntry forMetaData(ModuleComponentResolveMetadata metaData, long createTimestamp) {
        return new ModuleMetadataCacheEntry(TYPE_PRESENT, metaData.isChanging(), createTimestamp);
    }

    public boolean isMissing() {
        return type == TYPE_MISSING;
    }

    protected ModuleComponentResolveMetadata configure(MutableModuleComponentResolveMetadata input) {
        input.setChanging(isChanging);
        return input.asImmutable();
    }
}
