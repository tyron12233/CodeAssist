/*
 * Copyright 2021 the original author or authors.
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
package com.tyron.builder.api.internal.catalog;

import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.BuildTreeScopedCache;
import com.tyron.builder.internal.execution.workspace.WorkspaceProvider;
import com.tyron.builder.internal.execution.workspace.impl.DefaultImmutableWorkspaceProvider;
import com.tyron.builder.internal.file.FileAccessTimeJournal;

import java.io.Closeable;

public class DependenciesAccessorsWorkspaceProvider implements WorkspaceProvider, Closeable {
    private final DefaultImmutableWorkspaceProvider delegate;

    public DependenciesAccessorsWorkspaceProvider(BuildTreeScopedCache scopedCache, FileAccessTimeJournal fileAccessTimeJournal, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, StringInterner stringInterner) {
        this.delegate = DefaultImmutableWorkspaceProvider.withBuiltInHistory(
            scopedCache
                .cache("dependencies-accessors")
                .withDisplayName("dependencies-accessors"),
            fileAccessTimeJournal,
            inMemoryCacheDecoratorFactory,
            stringInterner);
    }

    @Override
    public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
        return delegate.withWorkspace(path, action);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
