package com.tyron.code;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.tyron.code.highlighter.attributes.CodeAssistTextAttributes;
import com.tyron.code.highlighter.attributes.CodeAssistTextAttributesProvider;
import com.tyron.code.highlighter.attributes.TextAttributesKeyUtils;
import com.tyron.code.project.CodeAssistJavaCoreProjectEnvironment;
import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionService;
import com.tyron.completion.impl.CompletionServiceImpl;
import com.tyron.completion.psi.codeInsight.completion.JavaCompletionContributor;
import com.tyron.completion.resolve.ResolveScopeEnlarger;
import com.tyron.completion.resolve.ResolveScopeProvider;
import com.tyron.editor.EditorFactory;
import com.tyron.editor.event.EditorFactoryListener;
import com.tyron.editor.impl.EditorFactoryImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore2;
import org.jetbrains.kotlin.com.intellij.codeInsight.FileModificationService2;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.diagnostic.PluginProblemReporterImpl;
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType;
import org.jetbrains.kotlin.com.intellij.lang.MetaLanguage;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.mock.MockApplication;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor;
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuard;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuardImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.PlainTextFileType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerationHandler;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.TestSourcesFilter;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ProjectFileIndexImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener;
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.kotlin.com.intellij.psi.impl.JavaClassSupersImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaAnonymousClassBaseRefOccurenceIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaFieldNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaFullClassNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaMethodParameterTypesIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaStaticMemberNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaStaticMemberTypeIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaSuperClassNameOccurenceIndex;
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider;
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndexImpl;
import org.jetbrains.kotlin.com.intellij.psi.stubs.SerializationManagerEx;
import org.jetbrains.kotlin.com.intellij.psi.stubs.SerializationManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.stubs.SerializedStubTree;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubElementTypeHolderEP;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatableIndexFactory;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatableIndexFactoryImpl;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndexStorage;
import org.jetbrains.kotlin.com.intellij.psi.util.JavaClassSupers;
import org.jetbrains.kotlin.com.intellij.util.Queries;
import org.jetbrains.kotlin.com.intellij.util.QueriesImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexInfrastructureExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableSetContributor;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableSetContributorModificationTracker;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.TestIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.ChangedFilesCollector;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.MapReduceIndexBase;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class CodeAssistApplicationEnvironment extends JavaCoreApplicationEnvironment {
    public CodeAssistApplicationEnvironment(@NotNull Disposable parentDisposable) {
        this(parentDisposable, false);
    }

    public CodeAssistApplicationEnvironment(@NotNull Disposable parentDisposable,
                                            boolean unitTestMode) {
        super(parentDisposable, unitTestMode);

        registerApplicationExtensionPoints();
        registerApplicationServices();
        registerFileTypes();
        registerLanguageSpecificExtensions();
        registerExtensionPointInstances();

        postInit();
    }

    @Override
    protected MockApplication createApplication(Disposable parentDisposable) {
        return new CodeAssistApplication(parentDisposable, Thread.currentThread());
    }

    protected void postInit() {
        System.setProperty("indexing.filename.over.vfs", "false");
        System.setProperty("intellij.idea.indices.debug", "true");
        Map<String, String> userProperties = Registry.getInstance().getUserProperties();
        userProperties.put("indexing.use.indexable.files.index", "true");

//        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//            try {
//                ((CoreStubIndex) StubIndex.getInstance()).flush();
//                ((CoreFileBasedIndex) FileBasedIndex.getInstance()).flush();
//                FileIdStorage.saveIds();
//                FSRecords.dispose();
//            } catch (IOException | StorageException e) {
//                throw new RuntimeException(e);
//            }
//        }));

        AsyncEventSupport.startListening();
        FSRecords.connect();
    }

    public void registerExtensionPointInstances() {
        ExtensionsAreaImpl extensionArea = getApplication().getExtensionArea();
        ExtensionPoint<FileBasedIndexExtension<?, ?>> fileBasedIndexExtensionExtensionPoint =
                extensionArea.getExtensionPoint(FileBasedIndexExtension.EXTENSION_POINT_NAME);
        fileBasedIndexExtensionExtensionPoint.registerExtension(new StubUpdatingIndex(),
                getParentDisposable());

        addExtension(AsyncEventSupport.EP_NAME, new ChangedFilesCollector());
        addExtension(FileBasedIndexExtension.EXTENSION_POINT_NAME, new FileTypeIndexImpl());
        addExtension(StubIndexExtension.EP_NAME, JavaFullClassNameIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaShortClassNameIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME,
                JavaAnonymousClassBaseRefOccurenceIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaSuperClassNameOccurenceIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaMethodNameIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaMethodParameterTypesIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaStaticMemberNameIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaStaticMemberTypeIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaAnnotationIndex.getInstance());
        addExtension(StubIndexExtension.EP_NAME, JavaFieldNameIndex.getInstance());

        ChangedFilesCollector changedFilesCollector = new ChangedFilesCollector();
        addExtension(AsyncEventSupport.EP_NAME, changedFilesCollector);
    }

    public void registerApplicationExtensionPoints() {
        ExtensionsAreaImpl extensionsArea = getApplication().getExtensionArea();

        extensionsArea.registerExtensionPoint(FileBasedIndexExtension.EXTENSION_POINT_NAME.getName(),
                FileBasedIndexExtension.class.getName(),
                ExtensionPoint.Kind.INTERFACE);

        extensionsArea.registerExtensionPoint(
                "com.intellij.testSourcesFilter",
                TestSourcesFilter.class.getName(),
                ExtensionPoint.Kind.INTERFACE
        );
        ;
        extensionsArea.registerExtensionPoint(StubIndexExtension.EP_NAME.getName(),
                StubIndexExtension.class.getName(),
                ExtensionPoint.Kind.INTERFACE);
        registerExtensionPoint(extensionsArea, "com.intellij.editorFactoryListener",
                EditorFactoryListener.class);
        registerExtensionPoint(extensionsArea, "com.intellij.editorFactoryDocumentListener",
                DocumentListener.class);
        registerApplicationExtensionPoint(IndexableSetContributor.EP_NAME,
                IndexableSetContributor.class);
        registerApplicationExtensionPoint(StubElementTypeHolderEP.EP_NAME,
                StubElementTypeHolderEP.class);
        registerApplicationExtensionPoint(AsyncEventSupport.EP_NAME, AsyncFileListener.class);
        registerApplicationExtensionPoint(FileBasedIndexInfrastructureExtension.EP_NAME,
                FileBasedIndexInfrastructureExtension.class);
        registerApplicationExtensionPoint(OrderEnumerationHandler.EP_NAME,
                OrderEnumerationHandler.Factory.class);
        registerApplicationExtensionPoint(ResolveScopeProvider.EP_NAME, ResolveScopeProvider.class);
        registerApplicationExtensionPoint(ResolveScopeEnlarger.EP_NAME, ResolveScopeEnlarger.class);
        registerApplicationExtensionPoint(DocumentWriteAccessGuard.EP_NAME,
                DocumentWriteAccessGuard.class);
        registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME, PsiAugmentProvider.class);
        registerApplicationExtensionPoint(JavaModuleSystem.EP_NAME, JavaModuleSystem.class);
        registerApplicationExtensionPoint(SmartPointerAnchorProvider.EP_NAME,
                SmartPointerAnchorProvider.class);
        registerApplicationExtensionPoint(ClsCustomNavigationPolicy.EP_NAME,
                ClsCustomNavigationPolicy.class);
        registerApplicationExtensionPoint(FileDocumentManagerListener.EP_NAME,
                FileDocumentManagerListener.class);
        registerApplicationExtensionPoint(MetaLanguage.EP_NAME, MetaLanguage.class);
    }

    public void registerFileTypes() {
        registerFileType(PlainTextFileType.INSTANCE, "json");
        registerFileType(JavaClassFileType.INSTANCE, "class");
    }

    public void registerApplicationServices() {
        registerApplicationService(EditorFactory.class, new EditorFactoryImpl());
        registerApplicationService(FileModificationService2.class, new CodeInsightUtilCore2());
        registerApplicationService(Queries.class, new QueriesImpl());
        registerApplicationService(StubUpdatableIndexFactory.class,
                new StubUpdatableIndexFactoryImpl());
        registerApplicationService(IndexableSetContributorModificationTracker.class,
                new IndexableSetContributorModificationTracker());
        registerApplicationService(SerializationManagerEx.class, new SerializationManagerImpl());
        registerApplicationService(FileBasedIndex.class, new CoreFileBasedIndex());
        registerApplicationService(StubIndex.class, new CoreStubIndex());
        registerApplicationService(PluginProblemReporterImpl.getInterface(),
                new PluginProblemReporterImpl());
        registerApplicationService(AsyncExecutionService.class, new AsyncExecutionService() {
            @Override
            protected @NonNull AppUIExecutor createWriteThreadExecutor(@NonNull ModalityState modalityState) {
                return new AppUIExecutor() {
                    @Override
                    public @NonNull AppUIExecutor later() {
                        return this;
                    }

                    @Override
                    public @NonNull AppUIExecutor expireWith(@NonNull Disposable disposable) {
                        return this;
                    }

                    @Override
                    public CancellablePromise<?> submit(@NonNull Runnable runnable) {
                        CompletableFuture<?> future = CompletableFuture.runAsync(runnable);
                        return new CancellablePromise<>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return future.cancel(mayInterruptIfRunning);
                            }

                            @Override
                            public boolean isCancelled() {
                                return future.isCancelled();
                            }

                            @Override
                            public boolean isDone() {
                                return future.isDone();
                            }

                            @Override
                            public Object get() throws ExecutionException, InterruptedException {
                                return future.get();
                            }

                            @Override
                            public Object get(long timeout,
                                              TimeUnit unit) throws ExecutionException,
                                    InterruptedException, TimeoutException {
                                return future.get(timeout, unit);
                            }
                        };
                    }
                };
            }

            @Override
            protected @NonNull <T> NonBlockingReadAction<T> buildNonBlockingReadAction(@NonNull Callable<T> callable) {

                return new NonBlockingReadAction<T>() {

                    CompletableFuture<T> future = new CompletableFuture<>();

                    @Override
                    public @NonNull NonBlockingReadAction<T> expireWhen(@NonNull BooleanSupplier booleanSupplier) {
                        return this;
                    }

                    @Override
                    public @NonNull NonBlockingReadAction<T> finishOnUiThread(@NonNull ModalityState modalityState,
                                                                              @NonNull Consumer<?
                                                                                      super T> consumer) {
                        future.whenComplete((t, throwable) -> consumer.accept(t));
                        return this;
                    }

                    @Override
                    public @NonNull NonBlockingReadAction<T> coalesceBy(Object... objects) {
                        return this;
                    }

                    @Override
                    public @NonNull CancellablePromise<T> submit(@NonNull Executor executor) {
                        return new CancellablePromise<>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return future.cancel(mayInterruptIfRunning);
                            }

                            @Override
                            public boolean isCancelled() {
                                return future.isCancelled();
                            }

                            @Override
                            public boolean isDone() {
                                return future.isDone();
                            }

                            @Override
                            public T get() throws ExecutionException, InterruptedException {
                                return future.get();
                            }

                            @Override
                            public T get(long timeout,
                                         TimeUnit unit) throws ExecutionException,
                                    InterruptedException, TimeoutException {
                                return future.get(timeout, unit);
                            }
                        };
                    }
                };
            }
        });
        registerApplicationService(CompletionService.class, new CompletionServiceImpl());
        registerApplicationService(TransactionGuard.class, new TransactionGuardImpl());
        registerApplicationService(JavaClassSupers.class, new JavaClassSupersImpl());
        registerApplicationService(CodeAssistTextAttributesProvider.class,
                new CodeAssistTextAttributesProvider() {
                    public CodeAssistTextAttributes getDefaultAttributes(TextAttributesKey textAttributesKey) {
                        String name = TextAttributesKeyUtils.getExternalName(textAttributesKey);

                        switch (Objects.requireNonNull(name)) {
                            case "DEFAULT_KEYWORD":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.KEYWORD,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_PARAMETER":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.IDENTIFIER_NAME,
                                        0,
                                        0,
                                        null);
                            case "DEFAULT_STRING":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.LITERAL,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_LINE_COMMENT":
                            case "DEFAULT_BLOCK_COMMENT":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.COMMENT,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_OPERATION_SIGN":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.OPERATOR,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_INVALID_STRING_ESCAPE":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.PROBLEM_ERROR,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_FUNCTION_DECLARATION":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.FUNCTION_NAME,
                                        Color.RED,
                                        0,
                                        null);
                        }


                        TextAttributesKey fallbackAttributeKey =
                                TextAttributesKeyUtils.getFallbackAttributeKey(textAttributesKey);
                        if (fallbackAttributeKey != null) {
                            return TextAttributesKeyUtils.getDefaultAttributes(fallbackAttributeKey);
                        }

                        return CodeAssistTextAttributes.DEFAULT;
                    }

                });
    }

    public void registerLanguageSpecificExtensions() {
        // language specific stuff
        addExplicitExtension(CompletionContributor.INSTANCE,
                JavaLanguage.INSTANCE,
                new JavaCompletionContributor());
    }
}
