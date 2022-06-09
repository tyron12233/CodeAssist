package com.tyron.builder.groovy.scripts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.DeleteSpec;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.ProcessOperations;
import com.tyron.builder.api.internal.file.DefaultFileOperations;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.HasScriptServices;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.internal.model.InstantiatorBackedObjectFactory;
import com.tyron.builder.api.internal.plugins.DefaultObjectConfigurationAction;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.api.resources.ResourceHandler;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.process.internal.ExecFactory;
import com.tyron.builder.util.ConfigureUtil;

import java.io.File;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Callable;

import groovy.lang.Closure;

public abstract class DefaultScript extends BasicScript {
    private static final Logger LOGGER = Logging.getLogger(Script.class);

    private FileOperations fileOperations;
    private ProcessOperations processOperations;
    private ProviderFactory providerFactory;
    private LoggingManager loggingManager;

    private ServiceRegistry scriptServices;

    @Override
    public void init(final Object target, ServiceRegistry services) {
        super.init(target, services);
        this.scriptServices = services;
        loggingManager = services.get(LoggingManager.class);
        if (target instanceof HasScriptServices) {
            HasScriptServices scriptServices = (HasScriptServices) target;
            fileOperations = scriptServices.getFileOperations();
            processOperations = scriptServices.getProcessOperations();
        } else {
            Instantiator instantiator = DirectInstantiator.INSTANCE;
            FileLookup fileLookup = services.get(FileLookup.class);
            FileCollectionFactory fileCollectionFactory = services.get(FileCollectionFactory.class);
            File sourceFile = getScriptSource().getResource().getLocation().getFile();
            if (sourceFile != null) {
                FileResolver resolver = fileLookup.getFileResolver(sourceFile.getParentFile());
                FileCollectionFactory fileCollectionFactoryWithBase = fileCollectionFactory.withResolver(resolver);
                fileOperations = DefaultFileOperations.createSimple(resolver, fileCollectionFactoryWithBase, services);
                processOperations = services.get(ExecFactory.class).forContext(resolver, fileCollectionFactoryWithBase, instantiator, new InstantiatorBackedObjectFactory(instantiator));
            } else {
                fileOperations = DefaultFileOperations.createSimple(fileLookup.getFileResolver(), fileCollectionFactory, services);
                processOperations = services.get(ExecFactory.class);
            }
        }

        providerFactory = services.get(ProviderFactory.class);
    }

    public FileResolver getFileResolver() {
        return fileOperations.getFileResolver();
    }

    private DefaultObjectConfigurationAction createObjectConfigurationAction() {
        ClassLoaderScope classLoaderScope = scriptServices.get(ClassLoaderScope.class);
        return new DefaultObjectConfigurationAction(
            getFileResolver(),
            scriptServices.get(ScriptPluginFactory.class),
            scriptServices.get(ScriptHandlerFactory.class),
            classLoaderScope,
            scriptServices.get(TextUriResourceLoader.Factory.class),
            getScriptTarget()
        );
    }

    @Override
    public void apply(Closure closure) {
        DefaultObjectConfigurationAction action = createObjectConfigurationAction();
        ConfigureUtil.configure(closure, action);
        action.execute();
    }

    @Override
    public void apply(Map options) {
        DefaultObjectConfigurationAction action = createObjectConfigurationAction();
        ConfigureUtil.configureByMap(options, action);
        action.execute();
    }

    @Override
    public ScriptHandler getBuildscript() {
        return scriptServices.get(ScriptHandler.class);
    }

    @Override
    public void buildscript(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getBuildscript());
    }

    @Override
    public File file(Object path) {
        return fileOperations.file(path);
    }

    @Override
    public File file(Object path, PathValidation validation) {
        return fileOperations.file(path, validation);
    }

    @Override
    public URI uri(Object path) {
        return fileOperations.uri(path);
    }

    @Override
    public ConfigurableFileCollection files(Object... paths) {
        return fileOperations.configurableFiles(paths);
    }

    @Override
    public ConfigurableFileCollection files(Object paths, Closure configureClosure) {
        return ConfigureUtil.configure(configureClosure, files(paths));
    }

    @Override
    public String relativePath(Object path) {
        return fileOperations.relativePath(path);
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir) {
        return fileOperations.fileTree(baseDir);
    }

    @Override
    public ConfigurableFileTree fileTree(Map<String, ?> args) {
        return fileOperations.fileTree(args);
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir, Closure configureClosure) {
        return ConfigureUtil.configure(configureClosure, fileOperations.fileTree(baseDir));
    }

    @Override
    public FileTree zipTree(Object zipPath) {
        return fileOperations.zipTree(zipPath);
    }

    @Override
    public FileTree tarTree(Object tarPath) {
        return fileOperations.tarTree(tarPath);
    }

    @Override
    public ResourceHandler getResources() {
        return fileOperations.getResources();
    }

    @Override
    public WorkResult copy(Closure closure) {
        return copy(ConfigureUtil.configureUsing(closure));
    }

    public WorkResult copy(Action<? super CopySpec> action) {
        return fileOperations.copy(action);
    }

    public WorkResult sync(Action<? super CopySpec> action) {
        return fileOperations.sync(action);
    }

    @Override
    public CopySpec copySpec(Closure closure) {
        return Actions.with(copySpec(), ConfigureUtil.configureUsing(closure));
    }

    public CopySpec copySpec() {
        return fileOperations.copySpec();
    }

    @Override
    public File mkdir(Object path) {
        return fileOperations.mkdir(path);
    }

    @Override
    public boolean delete(Object... paths) {
        return fileOperations.delete(paths);
    }

    public WorkResult delete(Action<? super DeleteSpec> action) {
        return fileOperations.delete(action);
    }

//    @Override
//    public ExecResult javaexec(Closure closure) {
//        return processOperations.javaexec(ConfigureUtil.configureUsing(closure));
//    }
//
//    @Override
//    public ExecResult javaexec(Action<? super JavaExecSpec> action) {
//        return processOperations.javaexec(action);
//    }
//
//    @Override
//    public ExecResult exec(Closure closure) {
//        return processOperations.exec(ConfigureUtil.configureUsing(closure));
//    }
//
//    @Override
//    public ExecResult exec(Action<? super ExecSpec> action) {
//        return processOperations.exec(action);
//    }

    @Override
    public <T> Provider<T> provider(Callable<T> value) {
        return providerFactory.provider(value);
    }

    @Override
    public LoggingManager getLogging() {
        return loggingManager;
    }

    @Override
    public Logger getLogger() {
        return LOGGER;
    }

    public String toString() {
        return "script";
    }


}
