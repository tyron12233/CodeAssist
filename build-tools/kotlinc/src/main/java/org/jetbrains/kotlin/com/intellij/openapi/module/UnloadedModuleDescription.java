package org.jetbrains.kotlin.com.intellij.openapi.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointer;

import java.util.List;

/**
 * Represents a module which is unloaded from the project. Such modules aren't shown in UI (except for a special 'Load/Unload Modules' dialog),
 * all of their contents is excluded from the project so it isn't indexed or compiled.
 */
@ApiStatus.Experimental
public interface UnloadedModuleDescription extends ModuleDescription {
  @NotNull
  List<VirtualFilePointer> getContentRoots();

  @NotNull
  List<String> getGroupPath();
}