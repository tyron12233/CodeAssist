package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerationHandler;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ProjectOrderEnumerator extends OrderEnumeratorBase {
  private final Project myProject;

  ProjectOrderEnumerator(@NotNull Project project, @Nullable OrderRootsCache rootsCache) {
    super(rootsCache);
    myProject = project;
  }

  @Override
  public void processRootModules(@NotNull Processor<? super Module> processor) {
    Module[] modules = myModulesProvider != null ? myModulesProvider.getModules() : ModuleManager.getInstance(myProject).getSortedModules();
    for (Module each : modules) {
      processor.process(each);
    }
  }

  @Override
  protected void forEach(@NotNull final PairProcessor<? super OrderEntry, ? super List<? extends OrderEnumerationHandler>> processor) {
    myRecursively = false;
    myWithoutDepModules = true;
    Set<Module> processed = new HashSet<>();
    processRootModules(module -> {
      processEntries(getRootModel(module), processed, true, getCustomHandlers(module), processor);
      return true;
    });
  }

  @Override
  public void forEachModule(@NotNull Processor<? super Module> processor) {
    processRootModules(processor);
  }

  @Override
  public boolean isRootModuleModel(@NotNull ModuleRootModel rootModel) {
    return true;
  }
}
