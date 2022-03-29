package com.tyron.kotlin_completion.compiler;

import android.util.Log;

import com.tyron.builder.project.api.KotlinModule;
import com.tyron.kotlin.completion.core.resolve.AnalysisResultWithProvider;
import com.tyron.kotlin.completion.core.resolve.CodeAssistAnalyzerFacadeForJVM;
import com.tyron.kotlin.completion.core.resolve.KotlinAnalyzer;

import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTraceContext;
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer;
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode;
import org.jetbrains.kotlin.resolve.calls.components.InferenceSession;
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo;
import org.jetbrains.kotlin.resolve.scopes.LexicalScope;
import org.jetbrains.kotlin.types.TypeUtils;
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices;

import java.io.Closeable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import kotlin.Pair;

public class Compiler implements Closeable {

    private final Set<Path> mJavaSourcePath;
    private final Set<Path> mClassPath;

    private final CompilationEnvironment mDefaultCompileEnvironment;
    private final VirtualFileSystem mLocalFileSystem;
    private final ReentrantLock mCompileLock = new ReentrantLock();

    private boolean closed = false;


    public Compiler(KotlinModule module, Set<Path> javaSourcePath, Set<Path> classPath) {
        mJavaSourcePath = javaSourcePath;
        mClassPath = classPath;
        mDefaultCompileEnvironment = new CompilationEnvironment(module, mJavaSourcePath, mClassPath);
        mLocalFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);
        UtilKt.setIdeaIoUseFallback();
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

    public KtFile createKtFile(String content, Path file, CompletionKind kind) {
        return (KtFile) createPsiFile(content, file, KotlinLanguage.INSTANCE, kind);
    }

    public PsiJavaFile createJavaFile(String content, Path file, CompletionKind kind) {
        return (PsiJavaFile) createPsiFile(content, file, JavaLanguage.INSTANCE, kind);
    }

    public PsiFileFactory psiFileFactoryFor(CompletionKind kind) {
        return PsiFileFactory.getInstance(mDefaultCompileEnvironment.getEnvironment().getProject());
    }

    public Pair<BindingContext, ComponentProvider> compileKtFile(KtFile file, Collection<KtFile> sourcePath) {
        return compileKtFiles(Collections.singletonList(file), sourcePath, CompletionKind.DEFAULT);
    }

    public Pair<BindingContext, ComponentProvider> compileKtFiles(Collection<? extends KtFile> files, Collection<KtFile> sourcePath, CompletionKind kind) {
        mCompileLock.lock();
        try {
            AnalysisResultWithProvider result =
                    KotlinAnalyzer.INSTANCE.analyzeFiles(sourcePath, files);
            return new Pair<>(result.getAnalysisResult().getBindingContext(), result.getComponentProvider());
//            Pair<ComponentProvider, BindingTraceContext> pair = mDefaultCompileEnvironment.createContainer(sourcePath);
//            ((LazyTopDownAnalyzer) pair.getFirst().resolve(LazyTopDownAnalyzer.class).getValue())
//                    .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files, DataFlowInfo.Companion.getEMPTY(), null);
//            return new Pair<>(pair.getSecond().getBindingContext(), pair.getFirst());
        } finally {
            mCompileLock.unlock();
        }
    }

    public CompilationEnvironment getDefaultCompileEnvironment() {
        return mDefaultCompileEnvironment;
    }

    public Pair<BindingContext, ComponentProvider> compileJavaFiles(Collection<? extends PsiJavaFile> files, Collection<KtFile> sourcePath, CompletionKind kind) {
        mCompileLock.lock();
        try {
            Pair<ComponentProvider, BindingTraceContext> pair = mDefaultCompileEnvironment.createContainer(sourcePath);
            ((LazyTopDownAnalyzer) pair.getFirst().resolve(LazyTopDownAnalyzer.class).getValue())
                    .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations,
                            files, DataFlowInfo.Companion.getEMPTY(), null);
            return new Pair<>(pair.getSecond().getBindingContext(), pair.getFirst());
        } finally {
            mCompileLock.unlock();
        }
    }

    public Pair<BindingContext, ComponentProvider> compileKtExpression(KtExpression expression, LexicalScope scopeWithImports, Collection<KtFile> sourcePath) {
        Log.d(null, "Compiling kt expression: " + expression.getText());
        mCompileLock.lock();
        try {
            Pair<ComponentProvider, BindingTraceContext> pair = mDefaultCompileEnvironment.createContainer(sourcePath);
            ExpressionTypingServices incrementalCompiler = pair.getFirst().create(ExpressionTypingServices.class);
            incrementalCompiler.getTypeInfo(
                    scopeWithImports,
                    expression,
                    TypeUtils.NO_EXPECTED_TYPE,
                    DataFlowInfo.Companion.getEMPTY(),
                    InferenceSession.Companion.getDefault(),
                    pair.getSecond(),
                    true);
            return new Pair<>(pair.getSecond().getBindingContext(), pair.getFirst());
        } finally {
            mCompileLock.unlock();
        }
    }

    public void updateConfiguration(CompilerConfiguration config) {
        mDefaultCompileEnvironment.updateConfiguration(config);
    }

    @Override
    public void close()  {
        if (!closed) {
            mDefaultCompileEnvironment.close();
            closed = true;
        } else {
            Log.w(null, "Compiler is already closed!");
        }
    }
}
