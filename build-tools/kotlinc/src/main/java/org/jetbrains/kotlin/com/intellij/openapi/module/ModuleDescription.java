package org.jetbrains.kotlin.com.intellij.openapi.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a description of a module which may be either loaded into the project or unloaded. Use this class only if you need to process
 * all modules including unloaded, in other cases {@link Module} should be used instead.
 *
 * @see UnloadedModuleDescription
 * @see LoadedModuleDescription
 */
@ApiStatus.Experimental
public interface ModuleDescription {
  @NotNull
  String getName();

  /**
   * Names of the modules on which the current module depend.
   */
  @NotNull
  List<String> getDependencyModuleNames();
}