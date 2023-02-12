package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

import java.util.*;
import java.util.stream.Collectors;

public final class ModuleWithDependentsScope extends GlobalSearchScope {
    private final Set<Module> myRootModules;
//    private final ProjectFileIndexImpl myProjectFileIndex;
    private final Set<Module> myModules = new HashSet<>();
    private final Set<Module> myProductionOnTestModules = new HashSet<>();

    ModuleWithDependentsScope(@NonNull Module module) {
        this(((ModuleImpl) module).getProject(), Collections.singleton(module));
    }

    public ModuleWithDependentsScope(@NonNull Project project,
                                     @NonNull Collection<? extends Module> modules) {
        super(project);
        myRootModules = new LinkedHashSet<>(modules);

//        myProjectFileIndex =
//                (ProjectFileIndexImpl) ProjectRootManager.getInstance(project).getFileIndex();

        myModules.addAll(myRootModules);

        ModuleIndex index = getModuleIndex(project);

        Collection<Module> walkingQueue = new HashSet<>();
        walkingQueue.addAll(myRootModules);
        for (Module current : walkingQueue) {
            Collection<Module> usages = index.allUsages.get(current);
            myModules.addAll(usages);
            walkingQueue.addAll(index.exportingUsages.get(current));

            if (myProductionOnTestModules.contains(current)) {
                myProductionOnTestModules.addAll(usages);
            }
            myProductionOnTestModules.addAll(index.productionOnTestUsages.get(current));
        }
    }

    private static final class ModuleIndex {
        final MultiMap<Module, Module> allUsages = new MultiMap<>();
        final MultiMap<Module, Module> exportingUsages = new MultiMap<>();
        final MultiMap<Module, Module> productionOnTestUsages = new MultiMap<>();
    }

    @NonNull
    private static ModuleIndex getModuleIndex(@NonNull Project project) {
//    return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
//      ModuleIndex index = new ModuleIndex();
//      for (Module module : ModuleManager.getInstance(project).getModules()) {
//        for (OrderEntry orderEntry : ModuleRootManager.getInstance(module).getOrderEntries()) {
//          if (orderEntry instanceof ModuleOrderEntry) {
//            Module referenced = ((ModuleOrderEntry)orderEntry).getModule();
//            if (referenced != null) {
//              index.allUsages.putValue(referenced, module);
//              if (((ModuleOrderEntry)orderEntry).isExported()) {
//                index.exportingUsages.putValue(referenced, module);
//              }
//              if (((ModuleOrderEntry)orderEntry).isProductionOnTestDependency()) {
//                index.productionOnTestUsages.putValue(referenced, module);
//              }
//            }
//          }
//        }
//      }
//      return CachedValueProvider.Result.create(index, ProjectRootManager.getInstance(project));
//    });
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        return contains(file, false);
    }

    boolean contains(@NonNull VirtualFile file, boolean fromTests) {
        Module moduleOfFile = getModuleForFile(file);
        if (moduleOfFile == null || !myModules.contains(moduleOfFile)) {
            return false;
        }
//        if (fromTests &&
//            !myProductionOnTestModules.contains(moduleOfFile) &&
//            !TestSourcesFilter.isTestSources(file, moduleOfFile.getProject())) {
//            return false;
//        }
        return true;
    }

    private Module getModuleForFile(VirtualFile file) {
        Module[] modules = ModuleManager.getInstance(getProject()).getModules();
        return Arrays.stream(modules)
                .filter(it -> it.getModuleScope().accept(file))
                .findAny()
                .orElse(null);
    }

    @Override
    public boolean isSearchInModuleContent(@NonNull Module module) {
        return myModules.contains(module);
    }

    @Override
    public boolean isSearchInLibraries() {
        return false;
    }



//    @NonNull
//    @Override
//    public Collection<UnloadedModuleDescription> getUnloadedModulesBelongingToScope() {
//        Project project = getProject();
//        ModuleManager moduleManager = ModuleManager.getInstance(Objects.requireNonNull(project));
//        return myRootModules.stream()
//                .flatMap(module -> DirectoryIndex.getInstance(project)
//                        .getDependentUnloadedModules(module)
//                        .stream())
//                .map(moduleManager::getUnloadedModuleDescription)
//                .filter(Objects::nonNull)
//                .collect(Collectors.toList());
//    }

    @Override
    public String toString() {
        return "Modules with dependents: (roots: [" +
               StringUtil.join(myRootModules, Module::getName, ", ") +
               "], including dependents: [" +
               StringUtil.join(myModules, Module::getName, ", ") +
               "])";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ModuleWithDependentsScope &&
               myModules.equals(((ModuleWithDependentsScope) o).myModules);
    }

    @Override
    public int calcHashCode() {
        return myModules.hashCode();
    }
}