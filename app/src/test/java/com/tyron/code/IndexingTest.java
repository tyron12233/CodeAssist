package com.tyron.code;

import static com.tyron.code.indexing.ProjectIndexer.index;

import com.tyron.code.project.CodeAssistJavaCoreProjectEnvironment;
import com.tyron.code.sdk.SdkManagerImpl;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.psi.search.PsiShortNamesCache;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.StandardProgressIndicatorBase;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingStamp;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue.IndexUpdateRunner;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.ChangedFilesCollector;
import org.jetbrains.kotlin.org.apache.log4j.Level;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;


public class IndexingTest {

    private static void preInit() throws Exception {
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
        FileIdStorage.loadIds();

        // so types are pre-registered
        IElementType annotationMethod = JavaStubElementTypes.ANNOTATION_METHOD;
        IElementType clazz = JavaStubElementTypes.CLASS;
    }

    private static void initSdk(Project project) {
        File classpathDir = Paths.get("")
                .toAbsolutePath()
                .getParent()
                .resolve("java-completion/src/test" + "/resources/classpath")
                .toFile();
        Sdk sdk = new Sdk("testSdk",
                project,
                classpathDir.getPath(),
                List.of(new File(classpathDir, "rt.jar"),
                        new File(classpathDir, "core-lambda-stubs.jar")));
        SdkManagerImpl sdkManager = (SdkManagerImpl) SdkManager.getInstance(project);
        sdkManager.setDefaultSdk(sdk);
    }

    private Project project;

    private void initEnvironment() {
        Disposable env = Disposer.newDisposable("env");
        CodeAssistApplicationEnvironment environment =
                new CodeAssistApplicationEnvironment(env, false);

        ChangedFilesCollector changedFilesCollector = new ChangedFilesCollector();
        environment.addExtension(AsyncEventSupport.EP_NAME, changedFilesCollector);
        AsyncEventSupport.startListening();

        File testProject = new File("src/test/resources/TestProject");
        VirtualFile projectVirtualFile =
                environment.getLocalFileSystem().findFileByIoFile(testProject);
        assert projectVirtualFile != null;

        CodeAssistJavaCoreProjectEnvironment projectEnvironment =
                new CodeAssistJavaCoreProjectEnvironment(env, environment, projectVirtualFile);
        project = projectEnvironment.getProject();
    }

    @Test
    public void testIndexingFrameworkGeneratesIndicesProperly() throws Exception {
        Instant start = Instant.now();
        try {
            doTest();
        } finally {
            System.out.println("Duration: " + Duration.between(start, Instant.now()).toMillis());
        }
    }

    public void doTest() throws Exception {
        preInit();

        initEnvironment();

        initSdk(project);
        FSRecords.connect();

        CoreFileBasedIndex fileBasedIndex = (CoreFileBasedIndex) FileBasedIndex.getInstance();
        fileBasedIndex.registerProjectFileSets(project);
        fileBasedIndex.loadIndexes();
        fileBasedIndex.waitUntilIndicesAreInitialized();
        fileBasedIndex.getRegisteredIndexes().extensionsDataWasLoaded();


        System.out.println("Initializing stub index");
        CoreStubIndex stubIndex = ((CoreStubIndex) StubIndex.getInstance());
        stubIndex.initializeStubIndexes();


        ProgressIndicator indicator = new StandardProgressIndicatorBase() {
            @Override
            public void setText(String text) {
                System.out.println(text);
            }

            @Override
            public void setText2(String text) {
                System.out.println("Indexing: " + text);
            }
        };
        ProgressManager.getInstance().executeProcessUnderProgress(() -> {
            try {
                index(project, fileBasedIndex);
            } catch (IndexUpdateRunner.IndexingInterruptedException e) {
                throw new ProcessCanceledException(e);
            }
        }, indicator);


        JavaShortClassNameIndex shortClassNameIndex = JavaShortClassNameIndex.getInstance();
        Collection<PsiClass> activity =
                shortClassNameIndex.get("Activity", project, new CustomSearchScope(project));
        assert !activity.isEmpty();

        PsiClass activityClass = activity.iterator().next();


        PsiClass superClass = activityClass.getSuperClass();
        assert superClass != null;

        System.out.println(superClass);

        PsiShortNamesCache shortNamesCache = PsiShortNamesCache.getInstance(project);

        assert shortNamesCache.getAllClassNames().length != 0;
        assert shortNamesCache.getAllFieldNames().length != 0;
        assert shortNamesCache.getAllMethodNames().length != 0;
        assert shortNamesCache.getAllFieldNames().length != 0;

        FileDocumentManagerBase fileDocumentManagerBase =
                (FileDocumentManagerBase) FileDocumentManager.getInstance();
        PsiDocumentManagerBase psiDocumentManagerBase =
                (PsiDocumentManagerBase) PsiDocumentManagerBase.getInstance(project);
        Document testDocument = new DocumentImpl("class Main { }");
        testDocument.addDocumentListener(psiDocumentManagerBase);
        testDocument.addDocumentListener(psiDocumentManagerBase.new PriorityEventCollector());

        PsiFile psiFile = psiDocumentManagerBase.getPsiFile(testDocument);
        System.out.println(psiFile);
    }

    private static class CustomSearchScope extends GlobalSearchScope {

        private final ProjectFileIndex index;

        public CustomSearchScope(Project project) {
            super(project);

            index = ProjectFileIndex.getInstance(project);
        }

        @Override
        public boolean isSearchInModuleContent(@NotNull Module module) {
            return true;
        }

        @Override
        public boolean isSearchInLibraries() {
            return true;
        }

        @Override
        public boolean contains(@NotNull VirtualFile virtualFile) {
            return index.isInLibrary(virtualFile) || index.isInProject(virtualFile);
        }
    }
}
