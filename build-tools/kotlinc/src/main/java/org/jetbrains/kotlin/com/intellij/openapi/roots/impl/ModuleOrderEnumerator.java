package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerationHandler;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;

import java.util.List;

public final class ModuleOrderEnumerator extends OrderEnumeratorBase {
  private final ModuleRootModel myRootModel;

  public ModuleOrderEnumerator(@NonNull ModuleRootModel rootModel, @Nullable OrderRootsCache cache) {
    super(cache);
    myRootModel = rootModel;
  }

  @Override
  public void processRootModules(@NonNull Processor<? super Module> processor) {
    processor.process(myRootModel.getModule());
  }

  @Override
  protected void forEach(@NonNull PairProcessor<? super OrderEntry, ? super List<? extends OrderEnumerationHandler>> processor) {
    processEntries(myRootModel, myRecursively ? CollectionFactory.createSmallMemoryFootprintSet(10) : null, true, getCustomHandlers(myRootModel.getModule()), processor);
  }

  @Override
  public boolean isRootModuleModel(@NonNull ModuleRootModel rootModel) {
    return rootModel.getModule() == myRootModel.getModule();
  }
}