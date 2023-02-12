package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.org.picocontainer.PicoContainer;

import java.util.Objects;

public class ModuleImpl extends MockComponentManager implements Module {
    private final String name;
    private final String filePath;
    private final Project project;
    private final ModuleScopeProvider moduleScopeProvider;

    public ModuleImpl(String name, Project project, String filePath) {
        super(project.getPicoContainer(), project);
        this.project = project;
        this.name = name;
        this.filePath = filePath;
        moduleScopeProvider = new ModuleScopeProviderImpl(this);
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull GlobalSearchScope getModuleScope() {
        return moduleScopeProvider.getModuleScope();
    }

    @Override
    public @NonNull GlobalSearchScope getModuleWithLibrariesScope() {
        return moduleScopeProvider.getModuleWithLibrariesScope();
    }

    @Override
    public @NonNull GlobalSearchScope getModuleWithDependenciesScope() {
        return moduleScopeProvider.getModuleWithDependenciesScope();
    }

    @Override
    public @NonNull GlobalSearchScope getModuleWithDependenciesAndLibrariesScope(boolean b) {
        return moduleScopeProvider.getModuleWithDependenciesAndLibrariesScope(b);
    }

    @Override
    public @NonNull GlobalSearchScope getModuleWithDependentsScope() {
        return moduleScopeProvider.getModuleWithDependentsScope();
    }

    @Override
    public @NonNull GlobalSearchScope getModuleTestsWithDependentsScope() {
        return moduleScopeProvider.getModuleTestsWithDependentsScope();
    }

    @Override
    public @NonNull GlobalSearchScope getModuleRuntimeScope(boolean b) {
        return moduleScopeProvider.getModuleRuntimeScope(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModuleImpl)) {
            return false;
        }

        ModuleImpl module = (ModuleImpl) o;

        if (!Objects.equals(name, module.name)) {
            return false;
        }
        return Objects.equals(filePath, module.filePath);
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (filePath != null ? filePath.hashCode() : 0);
        return result;
    }

    public Project getProject() {
        return project;
    }
}
