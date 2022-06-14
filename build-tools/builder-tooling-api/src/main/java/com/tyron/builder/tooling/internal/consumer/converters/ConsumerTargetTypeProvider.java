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

package com.tyron.builder.tooling.internal.consumer.converters;

import com.tyron.builder.tooling.internal.adapter.TargetTypeProvider;
import com.tyron.builder.tooling.internal.consumer.converters.BackwardsCompatibleIdeaModuleDependency;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppApplication;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppExecutable;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppLibrary;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppSharedLibrary;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppStaticLibrary;
import com.tyron.builder.tooling.internal.protocol.cpp.InternalCppTestSuite;
import com.tyron.builder.tooling.model.cpp.CppApplication;
import com.tyron.builder.tooling.model.cpp.CppBinary;
import com.tyron.builder.tooling.model.cpp.CppComponent;
import com.tyron.builder.tooling.model.cpp.CppExecutable;
import com.tyron.builder.tooling.model.cpp.CppLibrary;
import com.tyron.builder.tooling.model.cpp.CppSharedLibrary;
import com.tyron.builder.tooling.model.cpp.CppStaticLibrary;
import com.tyron.builder.tooling.model.cpp.CppTestSuite;
import com.tyron.builder.tooling.model.idea.IdeaModuleDependency;
import com.tyron.builder.tooling.model.idea.IdeaSingleEntryLibraryDependency;

import java.util.HashMap;
import java.util.Map;

public class ConsumerTargetTypeProvider implements TargetTypeProvider {
    private final Map<String, Class<?>> configuredTargetTypes = new HashMap<String, Class<?>>();

    public ConsumerTargetTypeProvider() {
        configuredTargetTypes.put(IdeaSingleEntryLibraryDependency.class.getCanonicalName(), IdeaSingleEntryLibraryDependency.class);
        configuredTargetTypes.put(IdeaModuleDependency.class.getCanonicalName(), BackwardsCompatibleIdeaModuleDependency.class);
    }

    @Override
    public <T> Class<? extends T> getTargetType(Class<T> initialTargetType, Object protocolObject) {
        Class<?>[] interfaces = protocolObject.getClass().getInterfaces();
        for (Class<?> i : interfaces) {
            if (configuredTargetTypes.containsKey(i.getName())) {
                return configuredTargetTypes.get(i.getName()).asSubclass(initialTargetType);
            }
        }
        if (initialTargetType.isAssignableFrom(CppComponent.class)) {
            if (protocolObject instanceof InternalCppApplication) {
                return CppApplication.class.asSubclass(initialTargetType);
            }
            if (protocolObject instanceof InternalCppLibrary) {
                return CppLibrary.class.asSubclass(initialTargetType);
            }
            if (protocolObject instanceof InternalCppTestSuite) {
                return CppTestSuite.class.asSubclass(initialTargetType);
            }
        } else if (initialTargetType.isAssignableFrom(CppBinary.class)) {
            if (protocolObject instanceof InternalCppExecutable) {
                return CppExecutable.class.asSubclass(initialTargetType);
            }
            if (protocolObject instanceof InternalCppSharedLibrary) {
                return CppSharedLibrary.class.asSubclass(initialTargetType);
            }
            if (protocolObject instanceof InternalCppStaticLibrary) {
                return CppStaticLibrary.class.asSubclass(initialTargetType);
            }
        }
        return initialTargetType;
    }
}
