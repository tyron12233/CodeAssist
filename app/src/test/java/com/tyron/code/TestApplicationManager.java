package com.tyron.code;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup;
import org.jetbrains.kotlin.com.intellij.core.CoreFileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.Getter;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingStamp;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.ChangedFilesCollector;
import org.jetbrains.kotlin.org.apache.log4j.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

public class TestApplicationManager {

    static {
        initializeTestApplicationEnvironment();
    }

    private static void preInit() {
//        Logger.setFactory(category -> new PrintingLogger(System.out));
        Logger.setFactory(category -> new Logger() {
            @Override
            public boolean isDebugEnabled() {
                return false;
            }

            @Override
            public void debug(@NonNls String s) {
                System.out.println(s);
            }

            @Override
            public void debug(@Nullable Throwable throwable) {

            }

            @Override
            public void debug(@NonNls String s, @Nullable Throwable throwable) {

            }

            @Override
            public void info(@NonNls String s) {

            }

            @Override
            public void info(@NonNls String s, @Nullable Throwable throwable) {

            }

            @Override
            public void warn(@NonNls String s, @Nullable Throwable throwable) {

            }

            @Override
            public void error(@NonNls String s,
                              @Nullable Throwable throwable,
                              String @NotNull ... strings) {

            }

            @Override
            public void setLevel(Level level) {

            }
        });
        UtilKt.setIdeaIoUseFallback();
        IdeaStandaloneExecutionSetup.INSTANCE.doSetup();
        System.setProperty("idea.home.path",
                Paths.get("").toAbsolutePath().getParent().resolve("TestHomePath").toString());
        System.setProperty("indexing.filename.over.vfs", "false");
        System.setProperty("intellij.idea.indices.debug", "true");
        Map<String, String> userProperties = Registry.getInstance().getUserProperties();
        userProperties.put("indexing.use.indexable.files.index", "true");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ((CoreStubIndex) StubIndex.getInstance()).flush();
                ((CoreFileBasedIndex) FileBasedIndex.getInstance()).flush();
                FileIdStorage.saveIds();
                IndexingStamp.flushCaches();
                FSRecords.dispose();
            } catch (IOException | StorageException e) {
                throw new RuntimeException(e);
            }
        }));
        try {
            FileIdStorage.loadIds();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        // so types are pre-registered
        IElementType annotationMethod = JavaStubElementTypes.ANNOTATION_METHOD;
        IElementType clazz = JavaStubElementTypes.CLASS;
    }

    private static void initializeTestApplicationEnvironment() {
        preInit();

        Disposable env = Disposer.newDisposable("env");
        CodeAssistApplicationEnvironment environment =
                new CodeAssistApplicationEnvironment(env, false);

        ChangedFilesCollector changedFilesCollector = new ChangedFilesCollector();
        environment.addExtension(AsyncEventSupport.EP_NAME, changedFilesCollector);
        AsyncEventSupport.startListening();

        ApplicationManager.setApplication(environment.getApplication(),
                CoreFileTypeRegistry::new, environment.getParentDisposable());
    }

    private static final TestApplicationManager ourInstance = new TestApplicationManager();

    private TestApplicationManager() {
    }
}
