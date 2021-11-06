package com.tyron.psi.completion;

import android.util.Log;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.config.ApiVersion;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.LanguageFeature;
import org.jetbrains.kotlin.config.LanguageVersion;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTraceContext;
import org.jetbrains.kotlin.resolve.CompilerEnvironment;
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer;
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode;
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo;
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

public class CompletionEnvironment implements AutoCloseable {
    private final Disposable mDisposable = Disposer.newDisposable();

    private final Set<Path> mJavaSourcePath;
    private final Set<Path> mClassPath;
    private final KotlinCoreEnvironment mEnvironment;

    private CompletionEngine mCompletionEngine;

    public static CompletionEnvironment newInstance(Collection<File> javaSourceRoots, Collection<File> kotlinSourceRoots, Set<File> classPath) {
        return new CompletionEnvironment(javaSourceRoots.stream().map(File::toPath).collect(Collectors.toSet()),
                kotlinSourceRoots.stream().map(File::toPath).collect(Collectors.toSet()),
                classPath.stream().map(File::toPath).collect(Collectors.toSet()));
    }

    public CompletionEnvironment(Set<Path> javaSourceRoots, Set<Path> kotlinSourceRoots, Set<Path> classPath) {
        mJavaSourcePath = javaSourceRoots;
        mClassPath = classPath;
        UtilKt.setIdeaIoUseFallback();

        mEnvironment = KotlinCoreEnvironment.createForProduction(mDisposable,
                getConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
        mEnvironment.addKotlinSourceRoots(kotlinSourceRoots.stream().map(Path::toFile).collect(Collectors.toList()));
        mCompletionEngine = new CompletionEngine(mEnvironment.getProjectEnvironment());
        mCompletionEngine.setCompletionEnvironment(this);

        createContainer(Collections.emptyList());
    }

    public Pair<ComponentProvider, BindingTraceContext> createContainer(Collection<KtFile> sourcePath) {
        CliBindingTrace trace = new CliBindingTrace();
        ComponentProvider container = TopDownAnalyzerFacadeForJVM.INSTANCE.createContainer(mEnvironment.getProject(),
                sourcePath, trace, mEnvironment.getConfiguration(), mEnvironment::createPackagePartProvider,
                (storageManager, ktFiles) ->
                        new FileBasedDeclarationProviderFactory(storageManager, (Collection<KtFile>) ktFiles),
                CompilerEnvironment.INSTANCE, TopDownAnalyzerFacadeForJVM.INSTANCE.newModuleSearchScope(mEnvironment.getProject(), mEnvironment.getSourceFiles()),
                Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        return Pair.create(container, trace);
    }

    public Pair<BindingContext, ComponentProvider> compileJavaFiles(Collection<? extends PsiJavaFile> files, Collection<KtFile> sourcePath) {
        try {
            Pair<ComponentProvider, BindingTraceContext> pair = createContainer(sourcePath);
            ((LazyTopDownAnalyzer) pair.first.resolve(LazyTopDownAnalyzer.class).getValue()).analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files, DataFlowInfo.Companion.getEMPTY(), null);
            Pair<BindingContext, ComponentProvider> bindingContextComponentProviderPair = Pair.create(pair.second.getBindingContext(), pair.first);
            return bindingContextComponentProviderPair;
        } finally {

        }
    }

    public void updateJavaSourceRoots(Set<Path> paths) {
        JvmContentRootsKt.addJavaSourceRoots(mEnvironment.getConfiguration(),
                paths.stream().map(Path::toFile).collect(Collectors.toList()));
    }

    public PsiJavaFile createJavaFile(String content, Path file) {
        return (PsiJavaFile) createPsiFile(content, file, JavaLanguage.INSTANCE);
    }


    public PsiFile createPsiFile(String content, Path file, Language language) {
        assert !content.contains("\r");
        PsiFile newFile = PsiFileFactory.getInstance(mEnvironment.getProjectEnvironment().getProject())
                .createFileFromText(file.toString(), language, content, true, false);
        assert newFile.getVirtualFile() != null;
        return newFile;
    }

    @Nullable
    public PsiFile getPsiFile(File file) {
        VirtualFile virtualFile = mEnvironment.getProjectEnvironment().getEnvironment().getLocalFileSystem()
                .refreshAndFindFileByPath(file.getAbsolutePath());
        if (virtualFile == null) {
            return null;
        }
        return PsiManager.getInstance(mEnvironment.getProject()).findFile(virtualFile);
    }

    public CompletionEngine getCompletionEngine() {
        return mCompletionEngine;
    }

    public KotlinCoreEnvironment getEnvironment() {
        return mEnvironment;
    }

    @Override
    public void close() {
        Disposer.dispose(mDisposable);
    }

    private CompilerConfiguration getConfiguration() {
        HashMap<LanguageFeature, LanguageFeature.State> map = new HashMap<>();
        for (LanguageFeature value : LanguageFeature.values()) {
            map.put(value, LanguageFeature.State.ENABLED);
        }
        LanguageVersionSettings settings = new LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion.createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                Collections.emptyMap(), map);
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME);
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, settings);
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        configuration.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true);
        configuration.put(JVMConfigurationKeys.NO_JDK, true);
        JvmContentRootsKt.addJvmClasspathRoots(configuration, mClassPath.stream().map(Path::toFile).collect(Collectors.toList()));
        JvmContentRootsKt.addJavaSourceRoots(configuration, mJavaSourcePath.stream().map(Path::toFile).collect(Collectors.toList()));
        return configuration;
    }
}
