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

package com.tyron.builder.internal.build.event.types;

import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.tooling.internal.protocol.events.InternalProjectConfigurationDescriptor;

import java.io.File;

public class DefaultProjectConfigurationDescriptor extends DefaultOperationDescriptor implements InternalProjectConfigurationDescriptor {

    private final File rootDir;
    private final String projectPath;

    public DefaultProjectConfigurationDescriptor(OperationIdentifier id, String displayName, OperationIdentifier parentId, File rootDir, String projectPath) {
        super(id, displayName, displayName, parentId);
        this.rootDir = rootDir;
        this.projectPath = projectPath;
    }

    @Override
    public File getRootDir() {
        return rootDir;
    }

    @Override
    public String getProjectPath() {
        return projectPath;
    }

}
