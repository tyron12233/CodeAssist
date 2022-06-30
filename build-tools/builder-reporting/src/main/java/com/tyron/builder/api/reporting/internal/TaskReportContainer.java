/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.api.reporting.internal;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.reporting.Report;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.service.ServiceRegistry;

public abstract class TaskReportContainer<T extends Report> extends DefaultReportContainer<T> {
    private final TaskInternal task;

    public TaskReportContainer(Class<? extends T> type, final Task task, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(type, locateInstantiator(task), callbackActionDecorator);
        this.task = (TaskInternal) task;
    }

    private static InstanceGenerator locateInstantiator(Task task) {
        ServiceRegistry projectServices = ((ProjectInternal) task.getProject()).getServices();
        return projectServices.get(InstantiatorFactory.class).decorateLenient(projectServices);
    }

    @Internal
    protected Task getTask() {
        return task;
    }
}
