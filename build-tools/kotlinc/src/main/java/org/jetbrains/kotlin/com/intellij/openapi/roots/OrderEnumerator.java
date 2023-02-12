package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.PathsList;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.NotNullFunction;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.List;

/**
 * <p>Interface for convenient processing dependencies of a module or a project. Allows to process {@link OrderEntry}s
 * and collect classes and source roots.</p>
 *
 * <p>Use {@link #orderEntries(Module)} or {@link ModuleRootModel#orderEntries()} to process dependencies of a module
 * and use {@link #orderEntries(Project)} to process dependencies of all modules in a project.</p>
 *
 * <p>Note that all configuration methods modify {@link OrderEnumerator} instance instead of creating a new one.</p>
 */

public abstract class OrderEnumerator {
  /**
   * Skip test dependencies
   *
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator productionOnly();

  /**
   * Skip runtime-only dependencies
   *
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator compileOnly();

  /**
   * Skip compile-only dependencies
   *
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator runtimeOnly();

  @NonNull
  public abstract OrderEnumerator withoutSdk();

  @NonNull
  public abstract OrderEnumerator withoutLibraries();

  @NonNull
  public abstract OrderEnumerator withoutDepModules();

  /**
   * Skip root module's entries
   * @return this
   */
  @NonNull
  public abstract OrderEnumerator withoutModuleSourceEntries();

  @NonNull
  public OrderEnumerator librariesOnly() {
    return withoutSdk().withoutDepModules().withoutModuleSourceEntries();
  }

  @NonNull
  public OrderEnumerator sdkOnly() {
    return withoutDepModules().withoutLibraries().withoutModuleSourceEntries();
  }

  public VirtualFile[] getAllLibrariesAndSdkClassesRoots() {
    return withoutModuleSourceEntries().withoutDepModules().recursively().exportedOnly().classes().usingCache().getRoots();
  }

  public VirtualFile[] getAllSourceRoots() {
    return recursively().exportedOnly().sources().usingCache().getRoots();
  }

  /**
   * Recursively process modules on which the module depends. This flag is ignored for modules imported from Maven because for such modules
   * transitive dependencies are propagated to the root module during importing.
   *
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator recursively();

  /**
   * Skip not exported dependencies. If this method is called after {@link #recursively()} direct non-exported dependencies won't be skipped
   *
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator exportedOnly();

  /**
   * Process only entries which satisfies the specified condition
   *
   * @param condition filtering condition
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator satisfying(@NonNull Condition<? super OrderEntry> condition);

  /**
   * Use {@code provider.getRootModel()} to process module dependencies
   *
   * @param provider provider
   * @return this instance
   */
  @NonNull
  public abstract OrderEnumerator using(@NonNull RootModelProvider provider);

  /**
   * Determine if, given the current enumerator settings and handlers for a module, should the
   * enumerator recurse to further modules based on the given ModuleOrderEntry?
   *
   * @param entry the ModuleOrderEntry in question (m1 -> m2)
   * @param handlers custom handlers registered to the module
   * @return true if the enumerator would have recursively processed the given ModuleOrderEntry.
   */
  public abstract boolean shouldRecurse(@NonNull ModuleOrderEntry entry, @NonNull List<? extends OrderEnumerationHandler> handlers);

  /**
   * @return {@link OrderRootsEnumerator} instance for processing classes roots
   */
  @NonNull
  public abstract OrderRootsEnumerator classes();

  /**
   * @return {@link OrderRootsEnumerator} instance for processing source roots
   */
  @NonNull
  public abstract OrderRootsEnumerator sources();

  /**
   * @param rootType root type
   * @return {@link OrderRootsEnumerator} instance for processing roots of the specified type
   */
  @NonNull
  public abstract OrderRootsEnumerator roots(@NonNull OrderRootType rootType);

  /**
   * @param rootTypeProvider custom root type provider
   * @return {@link OrderRootsEnumerator} instance for processing roots of the provided type
   */
  @NonNull
  public abstract OrderRootsEnumerator roots(@NonNull NotNullFunction<? super OrderEntry, ? extends OrderRootType> rootTypeProvider);

  /**
   * @return classes roots for all entries processed by this enumerator
   */
  public VirtualFile[] getClassesRoots() {
    return classes().getRoots();
  }

  /**
   * @return source roots for all entries processed by this enumerator
   */
  public VirtualFile[] getSourceRoots() {
    return sources().getRoots();
  }

  /**
   * @return list containing classes roots for all entries processed by this enumerator
   */
  @NonNull
  public PathsList getPathsList() {
    return classes().getPathsList();
  }

  /**
   * @return list containing source roots for all entries processed by this enumerator
   */
  @NonNull
  public PathsList getSourcePathsList() {
    return sources().getPathsList();
  }

  /**
   * Runs {@code processor.process()} for each entry processed by this enumerator.
   *
   * @param processor processor
   */
  public abstract void forEach(@NonNull Processor<? super OrderEntry> processor);

  /**
   * Runs {@code processor.process()} for each library processed by this enumerator.
   *
   * @param processor processor
   */
  public abstract void forEachLibrary(@NonNull Processor<? super Library> processor);

  /**
   * Runs {@code processor.process()} for each module processed by this enumerator.
   *
   * @param processor processor
   */
  public abstract void forEachModule(@NonNull Processor<? super Module> processor);

  /**
   * Passes order entries to the specified visitor.
   *
   * @param policy       the visitor to accept.
   * @param initialValue the default value to be returned by the visit process.
   * @return the value returned by the visitor.
   * @see OrderEntry#accept(RootPolicy, Object)
   */
  public abstract <R> R process(@NonNull RootPolicy<R> policy, R initialValue);

  /**
   * Creates new enumerator instance to process dependencies of {@code module}
   *
   * @param module module
   * @return new enumerator instance
   */
  @NonNull
  public static OrderEnumerator orderEntries(@NonNull Module module) {
    return ModuleRootManager.getInstance(module).orderEntries();
  }

  /**
   * Creates new enumerator instance to process dependencies of all modules in {@code project}. Only first level dependencies of
   * modules are processed so {@link #recursively()} option is ignored and {@link #withoutDepModules()} option is forced
   *
   * @param project project
   * @return new enumerator instance
   */
  @NonNull
  public static OrderEnumerator orderEntries(@NonNull Project project) {
    throw new UnsupportedOperationException();
//    return ProjectRootManager.getInstance(project).orderEntries();
  }
}