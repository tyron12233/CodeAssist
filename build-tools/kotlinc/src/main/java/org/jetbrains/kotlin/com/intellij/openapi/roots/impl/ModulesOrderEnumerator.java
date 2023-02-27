package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerationHandler;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApiStatus.Internal
public final class ModulesOrderEnumerator extends OrderEnumeratorBase {
  private final Collection<? extends Module> myModules;

  public ModulesOrderEnumerator(@NotNull Collection<? extends Module> modules) {
    super(null);
    myModules = modules;
  }

  @Override
  public void processRootModules(@NotNull Processor<? super Module> processor) {
    for (Module each : myModules) {
      processor.process(each);
    }
  }

  @Override
  protected void forEach(@NotNull PairProcessor<? super OrderEntry, ? super List<? extends OrderEnumerationHandler>> processor) {
    myRecursivelyExportedOnly = false;

    Set<Module> processed = new HashSet<>();
    for (Module module : myModules) {
      processEntries(getRootModel(module), processed, true, getCustomHandlers(module), processor);
    }
  }

  @Override
  public boolean isRootModuleModel(@NotNull ModuleRootModel rootModel) {
    return myModules.contains(rootModel.getModule());
  }
}
