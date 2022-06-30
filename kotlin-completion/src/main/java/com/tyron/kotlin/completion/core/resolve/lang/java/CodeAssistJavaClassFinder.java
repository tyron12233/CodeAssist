package com.tyron.kotlin.completion.core.resolve.lang.java;


import androidx.annotation.NonNull;

import com.tyron.builder.project.api.KotlinModule;
import com.tyron.kotlin.completion.core.model.KotlinEnvironment;

import org.jetbrains.kotlin.com.intellij.mock.MockProject;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.config.JvmTarget;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.load.java.AbstractJavaClassFinder;
import org.jetbrains.kotlin.load.java.JavaClassFinderImpl;
import org.jetbrains.kotlin.load.java.structure.JavaClass;
import org.jetbrains.kotlin.load.java.structure.JavaPackage;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer;

import java.util.Set;

public class CodeAssistJavaClassFinder extends AbstractJavaClassFinder {

    private KotlinModule module;
    private final JavaClassFinderImpl impl = new JavaClassFinderImpl();

    public CodeAssistJavaClassFinder(KotlinModule module) {
        this.module = module;
    }

    @Override
    public void initialize(BindingTrace trace,
                           KotlinCodeAnalyzer codeAnalyzer,
                           LanguageVersionSettings languageVersionSettings,
                           JvmTarget jvmTarget) {
        Project project = KotlinEnvironment.getEnvironment(module).getProject();
        setProjectInstance(project);
        impl.setScope(GlobalSearchScope.allScope(project));
        impl.initialize(trace, codeAnalyzer, languageVersionSettings, jvmTarget);
    }

    @Override
    public JavaClass findClass(@NonNull ClassId classId) {
        return impl.findClass(classId);
    }

    @Override
    public void setProjectInstance(@NonNull Project project) {
        impl.setProjectInstance(project);
    }

    @Override
    public JavaClass findClass(@NonNull Request request) {
        return impl.findClass(request);
    }

    @Override
    public JavaPackage findPackage(@NonNull FqName fqName) {
        return impl.findPackage(fqName);
    }

    @Override
    public Set<String> knownClassNamesInPackage(@NonNull FqName fqName) {
        return impl.knownClassNamesInPackage(fqName);
    }
}
