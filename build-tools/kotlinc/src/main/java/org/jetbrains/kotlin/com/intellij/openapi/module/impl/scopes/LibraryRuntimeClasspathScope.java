package org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.roots.JdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy;
import org.jetbrains.kotlin.com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.*;

public final class LibraryRuntimeClasspathScope extends GlobalSearchScope {
    private final FileIndexFacade myIndex;
    private final Object2IntMap<VirtualFile> myEntries = new Object2IntOpenHashMap<VirtualFile>() {
        @Override
        public int defaultReturnValue() {
            return -1;
        }
    };

    public LibraryRuntimeClasspathScope(@NonNull Project project,
                                        @NonNull Collection<? extends Module> modules) {
        super(project);

        myIndex = FileIndexFacade.getInstance(project);
        final Set<Sdk> processedSdk = new HashSet<>();
        final Set<Library> processedLibraries = new HashSet<>();
        final Set<Module> processedModules = new HashSet<>();
        final Condition<OrderEntry> condition = orderEntry -> {
            if (orderEntry instanceof ModuleOrderEntry) {
                final Module module = ((ModuleOrderEntry) orderEntry).getModule();
                return module != null && !processedModules.contains(module);
            }
            return true;
        };
        for (Module module : modules) {
            buildEntries(module, processedModules, processedLibraries, processedSdk, condition);
        }
    }

    public LibraryRuntimeClasspathScope(@NonNull Project project,
                                        @NonNull LibraryOrderEntry entry) {
        super(project);
        myIndex = FileIndexFacade.getInstance(project);
        addAll(myEntries, entry.getRootFiles(OrderRootType.CLASSES));
        addAll(myEntries, entry.getRootFiles(OrderRootType.SOURCES));
    }

    @Override
    public int calcHashCode() {
        return myEntries.hashCode();
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (object == null || object.getClass() != LibraryRuntimeClasspathScope.class) {
            return false;
        }

        final LibraryRuntimeClasspathScope that = (LibraryRuntimeClasspathScope) object;
        return that.myEntries.equals(myEntries);
    }

    private void buildEntries(@NonNull final Module module,
                              @NonNull final Set<? super Module> processedModules,
                              @NonNull final Set<? super Library> processedLibraries,
                              @NonNull final Set<? super Sdk> processedSdk,
                              @NonNull Condition<? super OrderEntry> condition) {
        if (!processedModules.add(module)) {
            return;
        }

        ModuleRootManager.getInstance(module)
                .orderEntries()
                .recursively()
                .satisfying(condition)
                .process(new RootPolicy<Object2IntMap<VirtualFile>>() {

                    @Override
                    public Object2IntMap<VirtualFile> visitLibraryOrderEntry(@NonNull LibraryOrderEntry libraryOrderEntry,
                                                                             final Object2IntMap<VirtualFile> value) {
                        final Library library = libraryOrderEntry.getLibrary();
                        if (library != null && processedLibraries.add(library)) {
                            addAll(value, libraryOrderEntry.getRootFiles(OrderRootType.CLASSES));
                            addAll(value, libraryOrderEntry.getRootFiles(OrderRootType.SOURCES));
                        }
                        return value;
                    }

                    @Override
                    public Object2IntMap<VirtualFile> visitModuleSourceOrderEntry(@NonNull final ModuleSourceOrderEntry moduleSourceOrderEntry,
                                                                                  final Object2IntMap<VirtualFile> value) {
                        processedModules.add(moduleSourceOrderEntry.getOwnerModule());
                        addAll(value, moduleSourceOrderEntry.getRootModel().getSourceRoots());
                        return value;
                    }

                    @Override
                    public Object2IntMap<VirtualFile> visitModuleOrderEntry(@NonNull ModuleOrderEntry moduleOrderEntry,
                                                                            Object2IntMap<VirtualFile> value) {
                        final Module depModule = moduleOrderEntry.getModule();
                        if (depModule != null) {
                            addAll(value,
                                    ModuleRootManager.getInstance(depModule).getSourceRoots());
                        }
                        return value;
                    }

                    @Override
                    public Object2IntMap<VirtualFile> visitJdkOrderEntry(@NonNull final JdkOrderEntry jdkOrderEntry,
                                                                         final Object2IntMap<VirtualFile> value) {
                        final Sdk jdk = jdkOrderEntry.getJdk();
                        if (jdk != null && processedSdk.add(jdk)) {
                            addAll(value, jdkOrderEntry.getRootFiles(OrderRootType.CLASSES));
                            addAll(value, jdkOrderEntry.getRootFiles(OrderRootType.SOURCES));
                        }
                        return value;
                    }
                }, myEntries);
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        return myEntries.containsKey(getFileRoot(file));
    }

    @Nullable
    private VirtualFile getFileRoot(@NonNull VirtualFile file) {
        Optional<VirtualFile> content = Arrays.stream(ModuleManager.getInstance(Objects.requireNonNull(
                        getProject())).getModules())
                .map(ModuleRootManager::getInstance)
                .map(manager -> manager.orderEntries().withoutLibraries().withoutSdk().sources().getRoots())
                .flatMap(Arrays::stream)
                .filter(it -> VfsUtilCore.isAncestor(it, file, false))
                .findAny();
        if (content.isPresent()) {
            return content.get();
        }

        Optional<VirtualFile> root = Arrays.stream(ModuleManager.getInstance(Objects.requireNonNull(
                        getProject())).getModules())
                .map(ModuleRootManager::getInstance)
                .map(manager -> manager.orderEntries().librariesOnly().classes().getRoots())
                .flatMap(Arrays::stream)
                .filter(it -> VfsUtilCore.isAncestor(it, file, false))
                .findAny();
        if (root.isPresent()) {
            return root.get();
        }

        Optional<VirtualFile> sourceRoot = Arrays.stream(ModuleManager.getInstance(getProject()).getModules())
                .map(ModuleRootManager::getInstance)
                .map(manager -> manager.orderEntries().librariesOnly().sources().getRoots())
                .flatMap(Arrays::stream)
                .filter(it -> VfsUtilCore.isAncestor(it, file, false))
                .findAny();
        return sourceRoot.orElse(null);
    }

    @Override
    public int compare(@NonNull VirtualFile file1, @NonNull VirtualFile file2) {
        if (file1.equals(file2)) {
            return 0;
        }
        final VirtualFile r1 = getFileRoot(file1);
        final VirtualFile r2 = getFileRoot(file2);
        final int i1 = myEntries.getInt(r1);
        final int i2 = myEntries.getInt(r2);
        if (i1 == i2) {
            return 0;
        }
        if (i1 == -1) {
            return -1;
        }
        if (i2 == -1) {
            return 1;
        }
        return i2 - i1;
    }

    @NonNull
    public List<VirtualFile> getRoots() {
        if (myEntries.isEmpty()) {
            return Collections.emptyList();
        }
        VirtualFile[] result = new VirtualFile[myEntries.size()];
        for (Object2IntMap.Entry<VirtualFile> entry : myEntries.object2IntEntrySet()) {
            result[entry.getIntValue()] = entry.getKey();
        }
        return Arrays.asList(result);
    }

    @Override
    public boolean isSearchInModuleContent(@NonNull Module aModule) {
        return false;
    }

    @Override
    public boolean isSearchInLibraries() {
        return true;
    }

    private static void addAll(Object2IntMap<? super VirtualFile> entries, VirtualFile[] files) {
        for (VirtualFile file : files) {
            if (!entries.containsKey(file)) {
                entries.put(file, entries.size());
            }
        }
    }
}