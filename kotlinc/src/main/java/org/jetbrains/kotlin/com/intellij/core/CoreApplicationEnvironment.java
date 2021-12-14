//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.jetbrains.kotlin.com.intellij.core;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.codeInsight.folding.CodeFoldingSettings;
import org.jetbrains.kotlin.com.intellij.concurrency.JobLauncher;
import org.jetbrains.kotlin.com.intellij.ide.plugins.DisabledPluginsState;
import org.jetbrains.kotlin.com.intellij.ide.plugins.PluginManagerCore;
import org.jetbrains.kotlin.com.intellij.lang.DefaultASTFactory;
import org.jetbrains.kotlin.com.intellij.lang.DefaultASTFactoryImpl;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageExtension;
import org.jetbrains.kotlin.com.intellij.lang.LanguageParserDefinitions;
import org.jetbrains.kotlin.com.intellij.lang.ParserDefinition;
import org.jetbrains.kotlin.com.intellij.lang.PsiBuilderFactory;
import org.jetbrains.kotlin.com.intellij.lang.impl.PsiBuilderFactoryImpl;
import org.jetbrains.kotlin.com.intellij.mock.MockApplication;
import org.jetbrains.kotlin.com.intellij.mock.MockFileDocumentManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationInfo;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.impl.ApplicationInfoImpl;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.command.impl.CoreCommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.BaseExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint.Kind;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionsArea;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeExtension;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.CoreProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.ClassExtension;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManagerListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.encoding.EncodingManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.CoreVirtualFilePointerManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.VirtualFileManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceService;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceServiceImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistryImpl;
import org.jetbrains.kotlin.com.intellij.psi.stubs.CoreStubTreeLoader;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubTreeLoader;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstanceEP;
import org.jetbrains.kotlin.com.intellij.util.graph.GraphAlgorithms;
import org.jetbrains.kotlin.com.intellij.util.graph.impl.GraphAlgorithmsImpl;
import org.jetbrains.kotlin.org.picocontainer.MutablePicoContainer;
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class CoreApplicationEnvironment {
    private final CoreFileTypeRegistry myFileTypeRegistry;
    protected final MockApplication myApplication;
    private final CoreLocalFileSystem myLocalFileSystem;

    protected final VirtualFileSystem myJarFileSystem;
    private final VirtualFileSystem myJrtFileSystem;

    private final Disposable myParentDisposable;
    private final boolean myUnitTestMode;

    public CoreApplicationEnvironment(Disposable parentDisposable) {
        this(parentDisposable, true);
    }

    public CoreApplicationEnvironment(Disposable parentDisposable, boolean unitTestMode) {
        super();
        this.myParentDisposable = parentDisposable;
        this.myUnitTestMode = unitTestMode;
        DisabledPluginsState.dontLoadDisabledPlugins();
        this.myFileTypeRegistry = new CoreFileTypeRegistry();
        this.myApplication = this.createApplication(this.myParentDisposable);
        ApplicationManager.setApplication(this.myApplication, () -> this.myFileTypeRegistry, this.myParentDisposable);
        this.myLocalFileSystem = this.createLocalFileSystem();
        this.myJarFileSystem = this.createJarFileSystem();
        this.myJrtFileSystem = this.createJrtFileSystem();
        this.registerApplicationService(FileDocumentManager.class, new MockFileDocumentManagerImpl(null, DocumentImpl::new));
        registerApplicationExtensionPoint(new ExtensionPointName<>("org.jetbrains.kotlin.com.intellij.virtualFileManagerListener"), VirtualFileManagerListener.class);
        List<VirtualFileSystem> fs = this.myJrtFileSystem != null ? Arrays.asList(this.myLocalFileSystem, this.myJarFileSystem, this.myJrtFileSystem) : Arrays.asList(this.myLocalFileSystem, this.myJarFileSystem);
        this.registerApplicationService(VirtualFileManager.class, new VirtualFileManagerImpl(fs));
        registerApplicationExtensionPoint(new ExtensionPointName<>("org.jetbrains.kotlin.com.intellij.virtualFileSystem"), KeyedLazyInstanceEP.class);
        registerApplicationExtensionPoint(DiagnosticSuppressor.Companion.getEP_NAME(), DiagnosticSuppressor.class);
        this.registerApplicationService(EncodingManager.class, new CoreEncodingRegistry());
        this.registerApplicationService(VirtualFilePointerManager.class, this.createVirtualFilePointerManager());
        this.registerApplicationService(DefaultASTFactory.class, new DefaultASTFactoryImpl());
        this.registerApplicationService(PsiBuilderFactory.class, new PsiBuilderFactoryImpl());
        this.registerApplicationService(ReferenceProvidersRegistry.class, new ReferenceProvidersRegistryImpl());
        this.registerApplicationService(StubTreeLoader.class, new CoreStubTreeLoader());
        this.registerApplicationService(PsiReferenceService.class, new PsiReferenceServiceImpl());
        this.registerApplicationService(ProgressManager.class, this.createProgressIndicatorProvider());
        this.registerApplicationService(JobLauncher.class, this.createJobLauncher());
        this.registerApplicationService(CodeFoldingSettings.class, new CodeFoldingSettings());
        this.registerApplicationService(CommandProcessor.class, new CoreCommandProcessor());
        this.registerApplicationService(GraphAlgorithms.class, new GraphAlgorithmsImpl());
        this.myApplication.registerService(ApplicationInfo.class, ApplicationInfoImpl.class);
    }

    public <T> void registerApplicationService(Class<T> serviceInterface, T serviceImplementation) {
        this.myApplication.registerService(serviceInterface, serviceImplementation);
    }


    protected VirtualFilePointerManager createVirtualFilePointerManager() {
        return new CoreVirtualFilePointerManager();
    }


    protected MockApplication createApplication(Disposable parentDisposable) {
        return new MockApplication(parentDisposable) {
            public boolean isUnitTestMode() {
                return CoreApplicationEnvironment.this.myUnitTestMode;
            }
        };
    }


    protected JobLauncher createJobLauncher() {
        return new JobLauncher() {
        };
    }


    protected ProgressManager createProgressIndicatorProvider() {
        return new CoreProgressManager();
    }


    protected VirtualFileSystem createJarFileSystem() {
        return new CoreJarFileSystem();
    }


    protected CoreLocalFileSystem createLocalFileSystem() {
        return new CoreLocalFileSystem();
    }

    @Nullable
    protected VirtualFileSystem createJrtFileSystem() {
        return null;
    }


    public MockApplication getApplication() {
        return this.myApplication;
    }


    public Disposable getParentDisposable() {
        return this.myParentDisposable;
    }

    public <T> void registerApplicationComponent(Class<T> interfaceClass, T implementation) {
        registerComponentInstance(this.myApplication.getPicoContainer(), interfaceClass, implementation);
        if (implementation instanceof Disposable) {
            Disposer.register(this.myApplication, (Disposable) implementation);
        }
    }

    public void registerFileType(FileType fileType, String extension) {
        this.myFileTypeRegistry.registerFileType(fileType, extension);
    }

    public void registerParserDefinition(ParserDefinition definition) {

        this.addExplicitExtension(LanguageParserDefinitions.INSTANCE,
                definition.getFileNodeType().getLanguage(), definition);
    }

    public static <T> void registerComponentInstance(MutablePicoContainer container, Class<T> key, T implementation) {
        container.unregisterComponent(key);
        container.registerComponentInstance(key, implementation);
    }

    public <T> void addExplicitExtension(LanguageExtension<T> instance, Language language, T object) {
        instance.addExplicitExtension(language, object, this.myParentDisposable);
    }

    public void registerParserDefinition(Language language, ParserDefinition parserDefinition) {

        this.addExplicitExtension(LanguageParserDefinitions.INSTANCE, (Language) language, parserDefinition);
    }

    public <T> void addExplicitExtension(FileTypeExtension<T> instance, FileType fileType, T object) {
        instance.addExplicitExtension(fileType, object, this.myParentDisposable);
    }

    public <T> void addExplicitExtension(ClassExtension<T> instance, Class<T> aClass, T object) {
        instance.addExplicitExtension(aClass, object, this.myParentDisposable);
    }

    public <T> void addExtension(ExtensionPointName<T> name, T extension) {
        ExtensionPoint<T> extensionPoint = Extensions.getRootArea().getExtensionPoint(name);
        extensionPoint.registerExtension(extension, this.myParentDisposable);
    }

    public static <T> void registerExtensionPoint(ExtensionsArea area, ExtensionPointName<T> extensionPointName, Class<? extends T> aClass) {
        registerExtensionPoint(area, extensionPointName.getName(), aClass);
    }

    public static <T> void registerExtensionPoint(ExtensionsArea area, BaseExtensionPointName<T> extensionPointName, Class<? extends T> aClass) {
        registerExtensionPoint(area, extensionPointName.getName(), aClass);
    }

    public static <T> void registerExtensionPoint(ExtensionsArea area, String name, Class<? extends T> aClass) {
        if (!area.hasExtensionPoint(name)) {
            Kind kind = !aClass.isInterface() && !Modifier.isAbstract(aClass.getModifiers()) ? Kind.BEAN_CLASS : Kind.INTERFACE;
            area.registerExtensionPoint(name, aClass.getName(), kind);
        }
    }

    public static <T> void registerDynamicExtensionPoint(ExtensionsArea area,
                                                         String name,
                                                         Class<? extends T> aClass) {
        if (!area.hasExtensionPoint(name)) {
            Kind kind = !aClass.isInterface() && !Modifier.isAbstract(aClass.getModifiers()) ? Kind.BEAN_CLASS : Kind.INTERFACE;
            area.registerDynamicExtensionPoint(name, aClass.getName(), kind);
        }
    }

    public static <T> void registerApplicationDynamicExtensionPoint(String name, Class<T> clazz) {
        registerDynamicExtensionPoint(Extensions.getRootArea(), name, clazz);
    }

    public static <T> void registerApplicationExtensionPoint(ExtensionPointName<T> extensionPointName, Class<? extends T> aClass) {
        registerExtensionPoint(Extensions.getRootArea(), extensionPointName, aClass);
    }

    public static void registerExtensionPointAndExtensions(Path pluginRoot, String fileName, ExtensionsArea area) {
        PluginManagerCore.registerExtensionPointAndExtensions(pluginRoot, fileName, area);
    }


    public CoreLocalFileSystem getLocalFileSystem() {
       return this.myLocalFileSystem;
    }


    public VirtualFileSystem getJarFileSystem() {
        return this.myJarFileSystem;
    }

    @Nullable
    public VirtualFileSystem getJrtFileSystem() {
        return this.myJrtFileSystem;
    }
}
