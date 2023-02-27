package org.jetbrains.kotlin.com.intellij.psi.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

public class PackagePrefixIndex {
  private static final Object LOCK = new Object();
  private MultiMap<String, Module> myMap;
  private final Project myProject;

  public PackagePrefixIndex(Project project) {
    myProject = project;
  }

  public Collection<String> getAllPackagePrefixes(@Nullable GlobalSearchScope scope) {
    MultiMap<String, Module> map;
    synchronized (LOCK) {
      map = myMap;
    }
    if (map != null) {
      return getAllPackagePrefixes(scope, map);
    }

    map = new MultiMap<>();
    for (final Module module : ModuleManager.getInstance(myProject).getModules()) {
      for (final ContentEntry entry : ModuleRootManager.getInstance(module).getContentEntries()) {
        for (final SourceFolder folder : entry.getSourceFolders()) {
          final String prefix = folder.getPackagePrefix();
          if (StringUtil.isNotEmpty(prefix)) {
            map.putValue(prefix, module);
          }
        }
      }
    }

    synchronized (LOCK) {
      if (myMap == null) {
        myMap = map;
      }
      return getAllPackagePrefixes(scope, myMap);
    }
  }

  private static Collection<String> getAllPackagePrefixes(final GlobalSearchScope scope, final MultiMap<String, Module> map) {
      if (scope == null) {
          return map.keySet();
      }

    List<String> result = new SmartList<>();
    for (final String prefix : map.keySet()) {
      for (final Module module : map.get(prefix)) {
        if (scope.isSearchInModuleContent(module)) {
          result.add(prefix);
          break;
        }
      }
    }
    return result;
  }
}