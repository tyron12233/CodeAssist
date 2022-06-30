/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.internal.deployment;

import com.tyron.builder.deployment.internal.Deployment;
import com.tyron.builder.deployment.internal.DeploymentHandle;
import com.tyron.builder.process.internal.ExecHandle;
import com.tyron.builder.process.internal.ExecHandleState;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;

import javax.inject.Inject;

public class JavaApplicationHandle implements DeploymentHandle {
    private final JavaExecHandleBuilder builder;
    private ExecHandle handle;

    @Inject
    public JavaApplicationHandle(JavaExecHandleBuilder builder) {
        this.builder = builder;
    }

    @Override
    public boolean isRunning() {
        return handle != null && handle.getState() == ExecHandleState.STARTED;
    }

    @Override
    public void start(Deployment deployment) {
        handle = builder.build().start();
    }

    @Override
    public void stop() {
        handle.abort();
    }
}
