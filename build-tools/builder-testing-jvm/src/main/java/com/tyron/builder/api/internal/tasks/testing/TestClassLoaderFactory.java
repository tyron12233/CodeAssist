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

package com.tyron.builder.api.internal.tasks.testing;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.initialization.ClassLoaderIds;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderCache;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.classpath.DefaultClassPath;

import javax.inject.Inject;

public class TestClassLoaderFactory implements Factory<ClassLoader> {

    private final ClassLoaderCache classLoaderCache;
    private final String testTaskPath;
    private final FileCollection testTaskClasspath;

    @Inject
    public TestClassLoaderFactory(
        ClassLoaderCache classLoaderCache,
        String testTaskPath,
        FileCollection testTaskClasspath
    ) {
        this.classLoaderCache = classLoaderCache;
        this.testTaskPath = testTaskPath;
        this.testTaskClasspath = testTaskClasspath;
    }

    @Override
    public ClassLoader create() {
        return classLoaderCache.get(ClassLoaderIds.testTaskClasspath(testTaskPath), DefaultClassPath.of(testTaskClasspath), null, null);
    }
}
