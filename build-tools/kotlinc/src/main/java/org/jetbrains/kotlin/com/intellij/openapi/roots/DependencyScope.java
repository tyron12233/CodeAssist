package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.w3c.dom.Element;

import java.util.function.Supplier;

/**
 * The table below specifies which order entries are used during compilation and runtime.
 * <table border=1>
 * <thead><td></td><td>Production<br/>Compile</td><td>Production<br/>Runtime</td>
 * <td>Test<br/>Compile</td><td>Test<br/>Runtime</td></thead>
 * <tbody>
 * <tr><td>{@link #COMPILE}</td>      <td>*</td><td>*</td><td>*</td><td>*</td></tr>
 * <tr><td>{@link #TEST}</td>         <td> </td><td> </td><td>*</td><td>*</td></tr>
 * <tr><td>{@link #RUNTIME}</td>      <td> </td><td>*</td><td> </td><td>*</td></tr>
 * <tr><td>{@link #PROVIDED}</td>     <td>*</td><td> </td><td>*</td><td>*</td></tr>
 * <tr><td>Production<br/>Output</td> <td> </td><td>*</td><td>*</td><td>*</td></tr>
 * <tr><td>Test<br/>Output</td>       <td> </td><td> </td><td> </td><td>*</td></tr>
 * </tbody>
 * </table>
 * <br>
 * 
 * Note that the way dependencies are processed may be changed by plugins if the project is imported from a build system. So values from
 * this enum are supposed to be used only to edit dependencies (via {@link ExportableOrderEntry#setScope}). If you need to determine which
 * dependencies are included into a classpath, use {@link OrderEnumerator}.
 */
public enum DependencyScope {
  COMPILE(() -> "compile", true, true, true, true),
  TEST(() -> "test", false, false, true, true),
  RUNTIME(() -> "runtime", false, true, false, true),
  PROVIDED(() -> "provided", true, false, true, true);
  private final @NonNull Supplier<String> myDisplayName;
  private final boolean myForProductionCompile;
  private final boolean myForProductionRuntime;
  private final boolean myForTestCompile;
  private final boolean myForTestRuntime;

  public static final String SCOPE_ATTR = "scope";

  DependencyScope(@NonNull Supplier<String> displayName,
                  boolean forProductionCompile,
                  boolean forProductionRuntime,
                  boolean forTestCompile,
                  boolean forTestRuntime) {
    myDisplayName = displayName;
    myForProductionCompile = forProductionCompile;
    myForProductionRuntime = forProductionRuntime;
    myForTestCompile = forTestCompile;
    myForTestRuntime = forTestRuntime;
  }

  @NonNull
  public static DependencyScope readExternal(@NonNull Element element) {
    String scope = element.getAttribute(SCOPE_ATTR);
    try {
      return valueOf(scope);
    }
    catch (IllegalArgumentException e) {
      return COMPILE;
    }
  }

  public void writeExternal(Element element) {
    if (this != COMPILE) {
      element.setAttribute(SCOPE_ATTR, name());
    }
  }

  @NonNull
  public String getDisplayName() {
    return myDisplayName.get();
  }

  public boolean isForProductionCompile() {
    return myForProductionCompile;
  }

  public boolean isForProductionRuntime() {
    return myForProductionRuntime;
  }

  public boolean isForTestCompile() {
    return myForTestCompile;
  }

  public boolean isForTestRuntime() {
    return myForTestRuntime;
  }

  @Override
  public String toString() {
    return getDisplayName();
  }
}