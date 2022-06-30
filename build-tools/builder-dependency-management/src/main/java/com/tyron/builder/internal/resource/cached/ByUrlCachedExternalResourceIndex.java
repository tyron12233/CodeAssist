/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.internal.resource.cached;

import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;

import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.serialize.BaseSerializerFactory;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

import java.nio.file.Path;

public class ByUrlCachedExternalResourceIndex extends DefaultCachedExternalResourceIndex<String> {
    public ByUrlCachedExternalResourceIndex(String persistentCacheFile, BuildCommencedTimeProvider timeProvider, ArtifactCacheLockingManager artifactCacheLockingManager, FileAccessTracker fileAccessTracker, Path commonRootPath) {
        super(persistentCacheFile, BaseSerializerFactory.STRING_SERIALIZER, timeProvider, artifactCacheLockingManager, fileAccessTracker, commonRootPath);
    }
}
