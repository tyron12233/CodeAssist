package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.remote.inet.InetAddressFactory;
import com.tyron.builder.api.internal.service.scopes.Scope;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.model.internal.DefaultObjectFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.DefaultFileLockContentionHandler;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class BasicGlobalScopeServices {

}
