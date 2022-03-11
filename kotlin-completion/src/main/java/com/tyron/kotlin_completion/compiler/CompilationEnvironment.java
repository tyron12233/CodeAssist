package com.tyron.kotlin_completion.compiler;

import com.tyron.builder.project.api.KotlinModule;
import com.tyron.kotlin.completion.core.model.KotlinEnvironment;
import com.tyron.kotlin.completion.core.resolve.CodeAssistAnalyzerFacadeForJVM;
import com.tyron.kotlin.completion.core.resolve.InjectionKt;
import com.tyron.kotlin.completion.core.resolve.KotlinPackagePartProvider;

import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.config.ApiVersion;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.JvmTarget;
import org.jetbrains.kotlin.config.LanguageFeature;
import org.jetbrains.kotlin.config.LanguageVersion;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.context.ContextKt;
import org.jetbrains.kotlin.context.ModuleContext;
import org.jetbrains.kotlin.context.MutableModuleContext;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl;
import org.jetbrains.kotlin.incremental.components.LookupTracker;
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.resolve.BindingTraceContext;
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver;
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver;
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory;
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory;
import org.jetbrains.kotlin.storage.StorageManager;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import kotlin.Pair;
import kotlin.collections.CollectionsKt;

public class CompilationEnvironment implements Closeable {

    private final KotlinModule mModule;
    private final Set<Path> mJavaSourcePath;
    private final Set<Path> mClassPath;

    private final Disposable mDisposable = Disposer.newDisposable();
    private final KotlinCoreEnvironment mEnvironment;
    private final KtPsiFactory mParser;

    public CompilationEnvironment(KotlinModule module,
                                  Set<Path> javaSourcePath,
                                  Set<Path> classPath) {
        mModule = module;
        mJavaSourcePath = javaSourcePath;
        mClassPath = classPath;

        UtilKt.setIdeaIoUseFallback();

        mEnvironment = KotlinEnvironment.getEnvironment(module);
        updateConfig(mEnvironment.getConfiguration());

        mParser = new KtPsiFactory(mEnvironment.getProject());
    }

    private void updateConfig(CompilerConfiguration configuration) {
        HashMap<LanguageFeature, LanguageFeature.State> map = new HashMap<>();
        for (LanguageFeature value : LanguageFeature.values()) {
            map.put(value, LanguageFeature.State.ENABLED);
        }
        LanguageVersionSettings settings =
                new LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion
                        .createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                                                Collections.emptyMap(), map);
        configuration.put(CommonConfigurationKeys.MODULE_NAME, mModule.getName());
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, settings);
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                          MessageCollector.Companion.getNONE());
        configuration.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true);
        configuration.put(JVMConfigurationKeys.NO_JDK, true);
        JvmContentRootsKt.addJvmClasspathRoots(configuration, mClassPath.stream().map(Path::toFile)
                .collect(Collectors.toList()));
        JvmContentRootsKt.addJavaSourceRoots(configuration,
                                             mJavaSourcePath.stream().map(Path::toFile)
                                                     .collect(Collectors.toList()));

    }

    private CompilerConfiguration getConfiguration() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        updateConfig(configuration);
        return configuration;
    }

    public Pair<ComponentProvider, BindingTraceContext> createContainer(Collection<KtFile> sourcePath) {
        return createContainer(Collections.emptyList(), sourcePath);
    }

    public Pair<ComponentProvider, BindingTraceContext> createContainer(Collection<KtFile> filesToAnalyze, Collection<KtFile> sourcePath) {
        CliBindingTrace trace = new CliBindingTrace();
        MutableModuleContext moduleContext = CodeAssistAnalyzerFacadeForJVM
                .createModuleContext(getEnvironment().getProject(),
                                     getEnvironment().getConfiguration(), true);
        ComponentProvider container = CodeAssistAnalyzerFacadeForJVM.INSTANCE
                .createContainer(trace, moduleContext, filesToAnalyze, sourcePath, getEnvironment(),
                                 mModule, JvmTarget.JVM_1_8);
        return new Pair<>(container, trace);
    }

    public void updateConfiguration(CompilerConfiguration config) {
        JvmTarget name = config.get(JVMConfigurationKeys.JVM_TARGET);
        if (name != null) {
            mEnvironment.getConfiguration().put(JVMConfigurationKeys.JVM_TARGET, name);
        }
    }

    public JvmTarget getJvmTargetFrom(String target) {
        switch (target) {
            case "default":
                return JvmTarget.DEFAULT;
            case "1.6":
                return JvmTarget.JVM_1_6;
            case "1.8":
                return JvmTarget.JVM_1_8;
            default:
                return null;
        }
    }

    @Override
    public void close() {
        Disposer.dispose(mDisposable);
    }

    public KtPsiFactory getParser() {
        return mParser;
    }

    public KotlinCoreEnvironment getEnvironment() {
        return mEnvironment;
    }
}
