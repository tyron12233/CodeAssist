/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.tasks.compile.incremental.IncrementalCompilerFactory;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.CachingClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.ClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.DefaultClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.cache.GeneralCompileCaches;
import com.tyron.builder.api.internal.tasks.compile.incremental.cache.UserHomeScopedCompileCaches;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.CachingClassSetAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.DefaultClassSetAnalyzer;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.initialization.JdkToolsInitializer;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.vfs.FileSystemAccess;

public class CompileServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new GradleScopeCompileServices());
    }

    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new UserHomeScopeServices());
    }

    private static class GradleScopeCompileServices {
        void configure(ServiceRegistration registration, JdkToolsInitializer initializer) {
            // Hackery
            initializer.initializeJdkTools();
        }

        public IncrementalCompilerFactory createIncrementalCompilerFactory(BuildOperationExecutor buildOperationExecutor, StringInterner interner, ClassSetAnalyzer classSetAnalyzer) {
            return new IncrementalCompilerFactory(buildOperationExecutor, interner, classSetAnalyzer);
        }

        CachingClassDependenciesAnalyzer createClassAnalyzer(StringInterner interner, GeneralCompileCaches cache) {
            return new CachingClassDependenciesAnalyzer(new DefaultClassDependenciesAnalyzer(interner), cache.getClassAnalysisCache());
        }

        CachingClassSetAnalyzer createClassSetAnalyzer(FileHasher fileHasher, StreamHasher streamHasher, ClassDependenciesAnalyzer classAnalyzer,
                                                       FileOperations fileOperations, FileSystemAccess fileSystemAccess, GeneralCompileCaches cache) {
            return new CachingClassSetAnalyzer(
                new DefaultClassSetAnalyzer(fileHasher, streamHasher, classAnalyzer, fileOperations),
                fileSystemAccess,
                cache.getClassSetAnalysisCache()
            );
        }
    }

    private static class UserHomeScopeServices {
        UserHomeScopedCompileCaches createCompileCaches(GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, StringInterner interner) {
            return new UserHomeScopedCompileCaches(cacheRepository, inMemoryCacheDecoratorFactory, interner);
        }
    }
}
