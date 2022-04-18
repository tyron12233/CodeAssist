package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.tasks.compile.incremental.IncrementalCompilerFactory;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.CachingClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.ClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.analyzer.DefaultClassDependenciesAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.cache.GeneralCompileCaches;
import com.tyron.builder.api.internal.tasks.compile.incremental.cache.UserHomeScopedCompileCaches;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.CachingClassSetAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.DefaultClassSetAnalyzer;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
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
//        void configure(ServiceRegistration registration, JdkToolsInitializer initializer) {
//            // Hackery
//            initializer.initializeJdkTools();
//        }

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
