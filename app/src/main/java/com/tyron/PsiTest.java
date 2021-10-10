package com.tyron;

import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.config.ApiVersion;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.LanguageFeature;
import org.jetbrains.kotlin.config.LanguageVersion;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.psiUtil.PsiUtilsKt;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTraceContext;
import org.jetbrains.kotlin.resolve.CompilerEnvironment;
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer;
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode;
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo;
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("all")
public class PsiTest implements Closeable {
    KotlinCoreEnvironment environment;
    Disposable disposable;

    ConstraintLayout.LayoutParams layout;

    ConstraintLayout constraintLayout;
    {
        UtilKt.setIdeaIoUseFallback();
        disposable = Disposer.newDisposable();

        HashMap<LanguageFeature, LanguageFeature.State> map = new HashMap<>();
        for (LanguageFeature value : LanguageFeature.values()) {
            map.put(value, LanguageFeature.State.ENABLED);
        }
        LanguageVersionSettings settings = new LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion.createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                Collections.emptyMap(), map);
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME);
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, settings);
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, new LoggingMessageCollector());
        configuration.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true);
        configuration.put(JVMConfigurationKeys.NO_JDK, true);

        Project project = FileManager.getInstance().getCurrentProject();
//        org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt.addJvmClasspathRoot(configuration, project.getJavaDirectory());
        environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
//        environment.addKotlinSourceRoots(Collections.singletonList(new File(project.getJavaDirectory(), "androidx/test/Test.kt")));
//
//        Compiler compiler = new Compiler();
//        Pair<BindingContext, ComponentProvider> provider = compiler.compileKtFiles(environment.getSourceFiles(), environment.getSourceFiles(), CompletionKind.DEFAULT);
//        new JavaCoreProjectEnvironment(disposable, environment.getProjectEnvironment().getEnvironment());

        PsiJavaFile psiJavaFile = (PsiJavaFile) PsiFileFactory.getInstance(environment.getProject()).createFileFromText(JavaLanguage.INSTANCE, "package test; public class Main {" +
                "public static void main(String[] args) {" +
                "" +
                "}" +
                "}");
        for (PsiElement child : psiJavaFile.getChildren()) {

        }
    }

    public Pair<ComponentProvider, BindingTraceContext> createContainer(Collection<KtFile> sourcePath) {
        CliBindingTrace trace = new CliBindingTrace();
        ComponentProvider container = TopDownAnalyzerFacadeForJVM.INSTANCE.createContainer(environment.getProject(),
                sourcePath, trace, environment.getConfiguration(), environment::createPackagePartProvider,
                (storageManager, ktFiles) -> new FileBasedDeclarationProviderFactory(storageManager, (Collection<KtFile>) ktFiles),
                CompilerEnvironment.INSTANCE, TopDownAnalyzerFacadeForJVM.INSTANCE.newModuleSearchScope(environment.getProject(), environment.getSourceFiles()),
                Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        return Pair.create(container, trace);
    }
    @Override
    public void close() {
        Disposer.dispose(disposable);
    }

    public enum CompletionKind {
        DEFAULT
    }

    private class Compiler {

        private boolean closed = false;
        private final VirtualFileSystem  localFileSystem;
        private final ReentrantLock compileLock = new ReentrantLock();

        {
            localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);
        }

        public PsiFile createPsiFile(String content) {
            return createPsiFile(content, Paths.get("dummy.virtual.kt"), KotlinLanguage.INSTANCE, CompletionKind.DEFAULT);
        }
        public PsiFile createPsiFile(String content, Path file, Language language, CompletionKind kind) {
            assert !content.contains("\r");
            PsiFile newFile = psiFileFactoryFor(kind).createFileFromText(file.toString(), language, content, true, false);
            assert newFile.getVirtualFile() != null;
            return newFile;
        }

        public PsiFile createKtFile(String content, Path file, CompletionKind kind) {
            return createPsiFile(content, file, KotlinLanguage.INSTANCE, kind);
        }

        public PsiFileFactory psiFileFactoryFor(CompletionKind kind) {
            return PsiFileFactory.getInstance(environment.getProject());
        }

        public Pair<BindingContext, ComponentProvider> compileKtFile(KtFile file, Collection<KtFile> sourcePath) {
            return compileKtFiles(Collections.singletonList(file), sourcePath, CompletionKind.DEFAULT);
        }

        public Pair<BindingContext, ComponentProvider> compileKtFiles(Collection<? extends KtFile> files, Collection<KtFile> sourcePath, CompletionKind kind) {
            compileLock.lock();
            try {
                Pair<ComponentProvider, BindingTraceContext> pair = createContainer(sourcePath);
                pair.first.create(LazyTopDownAnalyzer.class).analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files, DataFlowInfo.Companion.getEMPTY(), null);
                return Pair.create(pair.second.getBindingContext(), pair.first);
            } finally {
                compileLock.unlock();
            }
        }
    }


    private static class LoggingMessageCollector implements MessageCollector {

        @Override
        public void clear() {

        }

        @Override
        public boolean hasErrors() {
            return false;
        }

        @Override
        public void report(@NonNull CompilerMessageSeverity compilerMessageSeverity, @NonNull String s, @Nullable CompilerMessageSourceLocation compilerMessageSourceLocation) {
            Log.d("Kotlin compiler" ,compilerMessageSeverity + " " + s + " " + compilerMessageSourceLocation);
        }
    }
}
