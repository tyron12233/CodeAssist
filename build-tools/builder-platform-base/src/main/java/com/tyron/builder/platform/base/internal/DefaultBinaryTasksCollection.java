/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.platform.base.internal;

import org.apache.commons.lang3.StringUtils;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.UnknownDomainObjectException;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultDomainObjectSet;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.platform.base.BinaryTasksCollection;

public class DefaultBinaryTasksCollection extends DefaultDomainObjectSet<Task> implements BinaryTasksCollection {

    private final BinarySpecInternal binary;
    private final NamedEntityInstantiator<Task> taskInstantiator;

    public DefaultBinaryTasksCollection(BinarySpecInternal binarySpecInternal, NamedEntityInstantiator<Task> taskInstantiator, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        super(Task.class, collectionCallbackActionDecorator);
        this.binary = binarySpecInternal;
        this.taskInstantiator = taskInstantiator;
    }

    @Override
    public String taskName(String verb) {
        return verb + StringUtils.capitalize(binary.getProjectScopedName());
    }

    @Override
    public String taskName(String verb, String object) {
        return verb + StringUtils.capitalize(binary.getProjectScopedName()) + StringUtils.capitalize(object);
    }

    @Override
    public Task getBuild() {
        return binary.getBuildTask();
    }

    @Override
    public Task getCheck() {
        return binary.getCheckTask();
    }

    public <T extends Task> T findSingleTaskWithType(Class<T> type) {
        DomainObjectSet<T> tasks = withType(type);
        if (tasks.size() == 0) {
            return null;
        }
        if (tasks.size() > 1) {
            throw new UnknownDomainObjectException(String.format("Multiple tasks with type '%s' found.", type.getSimpleName()));
        }
        return tasks.iterator().next();
    }

    @Override
    public <T extends Task> void create(String name, Class<T> type, Action<? super T> config) {
        @SuppressWarnings("unchecked") T task = (T) taskInstantiator.create(name, (Class<TaskInternal>) type);
        add(task);
        config.execute(task);
    }
}
