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

package com.tyron.builder.language.base.internal;

import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Task;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.platform.base.BinarySpec;

public interface SourceTransformTaskConfig {
    String getTaskPrefix();
    Class<? extends DefaultTask> getTaskType();
    void configureTask(Task task, BinarySpec binary, LanguageSourceSet sourceSet, ServiceRegistry serviceRegistry);
}
