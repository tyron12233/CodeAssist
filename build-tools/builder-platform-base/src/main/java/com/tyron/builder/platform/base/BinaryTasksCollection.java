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

package com.tyron.builder.platform.base;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Task;
import com.tyron.builder.model.internal.core.UnmanagedStruct;

/**
 * A collection of tasks associated to a binary
 */
@Incubating
@UnmanagedStruct
public interface BinaryTasksCollection extends DomainObjectSet<Task> {
    /**
     * Generates a name for a task that performs some action on the binary.
     */
    String taskName(String verb);

    /**
     * Generates a name for a task that performs some action on the binary.
     */
    String taskName(String verb, String object);

    /**
     * The task that can be used to assemble this binary.
     */
    Task getBuild();

    /**
     * The task that can be used to check this binary.
     */
    Task getCheck();

    <T extends Task> void create(String name, Class<T> type, Action<? super T> config);

}
