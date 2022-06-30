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

package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.cache.Cache;
import com.tyron.builder.cache.ManualEvictionInMemoryCache;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.workspace.WorkspaceProvider;
import com.tyron.builder.internal.file.ReservedFileSystemLocation;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.File;

@NotThreadSafe
public class MutableTransformationWorkspaceServices implements TransformationWorkspaceServices, ReservedFileSystemLocation {

    private final Cache<UnitOfWork.Identity, Try<TransformationResult>> identityCache = new ManualEvictionInMemoryCache<>();
    private final Provider<Directory> baseDirectory;
    private final WorkspaceProvider workspaceProvider;
    private final ExecutionHistoryStore executionHistoryStore;

    public MutableTransformationWorkspaceServices(Provider<Directory> baseDirectory, ExecutionHistoryStore executionHistoryStore) {
        this.baseDirectory = baseDirectory;
        this.workspaceProvider = new MutableTransformationWorkspaceProvider();
        this.executionHistoryStore = executionHistoryStore;
    }

    @Override
    public WorkspaceProvider getWorkspaceProvider() {
        return workspaceProvider;
    }

    @Override
    public Cache<UnitOfWork.Identity, Try<TransformationResult>> getIdentityCache() {
        return identityCache;
    }

    @Override
    public Provider<? extends FileSystemLocation> getReservedFileSystemLocation() {
        return baseDirectory;
    }

    private class MutableTransformationWorkspaceProvider implements WorkspaceProvider {
        @Override
        public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
            File workspaceDir = new File(baseDirectory.get().getAsFile(), path);
            return action.executeInWorkspace(workspaceDir, executionHistoryStore);
        }
    }
}
