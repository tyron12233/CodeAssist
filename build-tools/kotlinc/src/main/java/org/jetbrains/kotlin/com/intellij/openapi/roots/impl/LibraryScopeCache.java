package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleWithDependentsScope;
import org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes.JdkScope;
import org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes.LibraryRuntimeClasspathScope;
import org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes.ModulesScope;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.roots.JdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.DelegatingGlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentFactoryMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class LibraryScopeCache {

    private final LibrariesOnlyScope myLibrariesOnlyScope;

    public static LibraryScopeCache getInstance(@NonNull Project project) {
        return project.getService(LibraryScopeCache.class);
    }

    private final Project myProject;
    private final ConcurrentMap<Module[], GlobalSearchScope> myLibraryScopes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GlobalSearchScope> mySdkScopes = new ConcurrentHashMap<>();
    private final Map<List<? extends OrderEntry>, GlobalSearchScope> myLibraryResolveScopeCache = ConcurrentFactoryMap.createMap(key -> calcLibraryScope(key));
    private final Map<List<? extends OrderEntry>, GlobalSearchScope> myLibraryUseScopeCache = ConcurrentFactoryMap.createMap(key -> calcLibraryUseScope(key));

    public LibraryScopeCache(@NonNull Project project) {
        myProject = project;
        myLibrariesOnlyScope = new LibrariesOnlyScope(GlobalSearchScope.allScope(myProject), myProject);
    }

    void clear() {
        myLibraryScopes.clear();
        mySdkScopes.clear();
        myLibraryResolveScopeCache.clear();
        myLibraryUseScopeCache.clear();
    }

    public @NonNull GlobalSearchScope getLibrariesOnlyScope() {
        return myLibrariesOnlyScope;
    }

    private @NonNull GlobalSearchScope getScopeForLibraryUsedIn(@NonNull List<? extends Module> modulesLibraryIsUsedIn) {
        Module[] array = modulesLibraryIsUsedIn.toArray(Module.EMPTY_ARRAY);
        GlobalSearchScope scope = myLibraryScopes.get(array);
        return scope != null ? scope : ConcurrencyUtil.cacheOrGet(myLibraryScopes, array,
                new LibraryRuntimeClasspathScope(myProject, modulesLibraryIsUsedIn));
    }

    /**
     * Resolve references in SDK/libraries in context of all modules which contain it, but prefer classes from the same library
     * @param orderEntries the order entries that reference a particular SDK/library
     * @return a cached resolve scope
     */
    public @NonNull GlobalSearchScope getLibraryScope(@NonNull List<? extends OrderEntry> orderEntries) {
        return myLibraryResolveScopeCache.get(orderEntries);
    }

    /**
     * Returns a scope containing all modules depending on the library transitively plus all the project's libraries
     * @param orderEntries the order entries that reference a particular SDK/library
     * @return a cached use scope
     */
    public @NonNull GlobalSearchScope getLibraryUseScope(@NonNull List<? extends OrderEntry> orderEntries) {
        return myLibraryUseScopeCache.get(orderEntries);
    }

    private @NonNull GlobalSearchScope calcLibraryScope(@NonNull List<? extends OrderEntry> orderEntries) {
        List<Module> modulesLibraryUsedIn = new ArrayList<>();

        LibraryOrderEntry lib = null;
        for (OrderEntry entry : orderEntries) {
            if (entry instanceof JdkOrderEntry) {
                return getScopeForSdk((JdkOrderEntry)entry);
            }

            if (entry instanceof LibraryOrderEntry) {
                lib = (LibraryOrderEntry)entry;
                modulesLibraryUsedIn.add(entry.getOwnerModule());
            }
            else if (entry instanceof ModuleOrderEntry) {
                modulesLibraryUsedIn.add(entry.getOwnerModule());
            }
        }

        Comparator<Module> comparator = Comparator.comparing(Module::getName);
        modulesLibraryUsedIn.sort(comparator);
        List<? extends Module> uniquesList = ContainerUtil2.removeDuplicatesFromSorted(modulesLibraryUsedIn, comparator);

        GlobalSearchScope allCandidates = uniquesList.isEmpty() ? myLibrariesOnlyScope : getScopeForLibraryUsedIn(uniquesList);
        if (lib != null) {
            final LibraryRuntimeClasspathScope preferred = new LibraryRuntimeClasspathScope(myProject, lib);
            // prefer current library
            return new DelegatingGlobalSearchScope(allCandidates, preferred) {
                @Override
                public int compare(@NonNull VirtualFile file1, @NonNull VirtualFile file2) {
                    boolean c1 = preferred.contains(file1);
                    boolean c2 = preferred.contains(file2);
                    if (c1 && !c2) return 1;
                    if (c2 && !c1) return -1;

                    return super.compare(file1, file2);
                }
            };
        }
        return allCandidates;
    }


    public @NonNull GlobalSearchScope getScopeForSdk(@NonNull JdkOrderEntry jdkOrderEntry) {
        final String jdkName = jdkOrderEntry.getJdkName();
        if (jdkName == null) return GlobalSearchScope.allScope(myProject);
        GlobalSearchScope scope = mySdkScopes.get(jdkName);
        if (scope == null) {
            scope = new JdkScope(myProject, jdkOrderEntry);
            return ConcurrencyUtil.cacheOrGet(mySdkScopes, jdkName, scope);
        }
        return scope;
    }

    private @NonNull GlobalSearchScope calcLibraryUseScope(@NonNull List<? extends OrderEntry> entries) {
        Set<Module> modulesWithLibrary = new HashSet<>(entries.size());
        Set<Module> modulesWithSdk = new HashSet<>(entries.size());
        for (OrderEntry entry : entries) {
            (entry instanceof JdkOrderEntry ? modulesWithSdk : modulesWithLibrary).add(entry.getOwnerModule());
        }
        modulesWithSdk.removeAll(modulesWithLibrary);

        // optimisation: if the library attached to all modules (often the case with JDK) then replace the 'union of all modules' scope with just 'project'
        if (modulesWithSdk.size() + modulesWithLibrary.size() == ModuleManager.getInstance(myProject).getModules().length) {
            return GlobalSearchScope.allScope(myProject);
        }

        List<GlobalSearchScope> united = new ArrayList<>();
        if (!modulesWithSdk.isEmpty()) {
            united.add(new ModulesScope(modulesWithSdk, myProject));
            united.add(myLibrariesOnlyScope.intersectWith(new LibraryRuntimeClasspathScope(myProject, modulesWithSdk)));
        } else {
            united.add(myLibrariesOnlyScope);
        }

        if (!modulesWithLibrary.isEmpty()) {
            united.add(new ModuleWithDependentsScope(myProject, modulesWithLibrary));
        }

        return GlobalSearchScope.union(united.toArray(GlobalSearchScope.EMPTY_ARRAY));
    }

    private static final class LibrariesOnlyScope extends DelegatingGlobalSearchScope {
        private final FileIndexFacade myIndex;

        private LibrariesOnlyScope(@NonNull GlobalSearchScope original, @NonNull Project project) {
            super(project, original);
            myIndex = FileIndexFacade.getInstance(project);
        }

        @Override
        public boolean contains(@NonNull VirtualFile file) {
            return super.contains(file) && myIndex.isInLibraryClasses(file) || myIndex.isInLibrarySource(file);
        }

        @Override
        public boolean isSearchInModuleContent(@NonNull Module aModule) {
            return false;
        }

        @Override
        public boolean isSearchInLibraries() {
            return true;
        }

        @Override
        public String toString() {
            return "Libraries only in (" + myBaseScope + ")";
        }
    }
}
