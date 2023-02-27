package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.kotlin.com.intellij.core.CoreFileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootsEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ProjectFileIndexImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.VfsUtil;
import org.jetbrains.kotlin.com.intellij.psi.search.impl.VirtualFileEnumeration;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.BitUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ModuleWithDependenciesScope extends GlobalSearchScope {
    public static final int COMPILE_ONLY = 0x01;
    public static final int LIBRARIES = 0x02;
    public static final int MODULES = 0x04;
    public static final int TESTS = 0x08;
    public static final int CONTENT = 0x20;

    //    @MagicConstant(value = {COMPILE_ONLY, LIBRARIES, MODULES, TESTS, CONTENT})
    @interface ScopeConstant {
    }

    private static final Key<CachedValue<ConcurrentMap<Integer, VirtualFileEnumeration>>>
            CACHED_FILE_ID_ENUMERATIONS_KEY = Key.create("CACHED_FILE_ID_ENUMERATIONS");

    private final ModuleImpl myModule;
    @ScopeConstant
    private final int myOptions;
    private final ProjectFileIndexImpl myProjectFileIndex;

    private volatile Set<Module> myModules;
    private final Object2IntMap<VirtualFile> myRoots;
    private final UserDataHolderBase myUserDataHolderBase = new UserDataHolderBase();

    ModuleWithDependenciesScope(@NonNull Module module, @ScopeConstant int options) {
        super(((ModuleImpl) module).getProject());
        myModule = (ModuleImpl) module;
        myOptions = options;
        myProjectFileIndex =
                (ProjectFileIndexImpl) ProjectRootManager.getInstance(((ModuleImpl) module).getProject())
                        .getFileIndex();
        myRoots = calcRoots(null);
    }

    private Object2IntMap<VirtualFile> calcRoots(@Nullable ModelBranch branch) {
        Set<VirtualFile> roots = new LinkedHashSet<>();
        if (hasOption(CONTENT)) {
            Set<Module> modules = calcModules();
            myModules = new HashSet<>(modules);
            for (Module m : modules) {
                for (ContentEntry entry : ModuleRootManager.getInstance(m).getContentEntries()) {
                    ContainerUtil.addIfNotNull(roots, entry.getFile());
                }
            }
        } else {
            OrderRootsEnumerator enumerator = getOrderEnumeratorForOptions().roots(entry -> {
                if (entry instanceof ModuleOrderEntry || entry instanceof ModuleSourceOrderEntry) {
                    return OrderRootType.SOURCES;
                }
                return OrderRootType.CLASSES;
            });
            Collections.addAll(roots, enumerator.getRoots());
        }
        int i = 1;
        Object2IntMap<VirtualFile> map = new Object2IntOpenHashMap<>(roots.size());
        for (VirtualFile root : roots) {
            map.put(root, i++);
        }
        return map;
    }

    private OrderEnumerator getOrderEnumeratorForOptions() {
        OrderEnumerator en = ModuleRootManager.getInstance(myModule).orderEntries();
        en.recursively();
        if (hasOption(COMPILE_ONLY)) {
            en.exportedOnly().compileOnly();
        }
        if (!hasOption(LIBRARIES)) {
            en.withoutLibraries().withoutSdk();
        }
        if (!hasOption(MODULES)) {
            en.withoutDepModules();
        }
        if (!hasOption(TESTS)) {
            en.productionOnly();
        }
        return en;
    }

    //  @NonNull
    private Set<Module> calcModules() {
        // In the case that hasOption(CONTENT), the order of the modules set matters for
        // ordering the content roots, so use a LinkedHashSet
        Set<Module> modules = new LinkedHashSet<>();
        OrderEnumerator en = getOrderEnumeratorForOptions();
        en.forEach(each -> {
            if (each instanceof ModuleOrderEntry) {
                ContainerUtil.addIfNotNull(modules, ((ModuleOrderEntry) each).getModule());
            } else if (each instanceof ModuleSourceOrderEntry) {
                ContainerUtil.addIfNotNull(modules, each.getOwnerModule());
            }
            return true;
        });
        return modules;
    }

    @NonNull
    public Module getModule() {
        return myModule;
    }

    private boolean hasOption(@ScopeConstant int option) {
        return BitUtil.isSet(myOptions, option);
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return hasOption(COMPILE_ONLY) ? "compile only: " +
                                         myModule.getName() //IndexingBundle.message("search
                // .scope.module", myModule.getName())
                : "runtime: " +
                  myModule.getName(); //IndexingBundle.message("search.scope.module.runtime",
        // myModule.getName());
    }

    @Override
    public boolean isSearchInModuleContent(@NonNull Module aModule) {
        Set<Module> allModules = myModules;
        if (allModules == null) {
            myModules = allModules = new HashSet<>(calcModules());
        }
        return allModules.contains(aModule);
    }

    @Override
    public boolean isSearchInModuleContent(@NonNull Module aModule, boolean testSources) {
        return isSearchInModuleContent(aModule) && (hasOption(TESTS) || !testSources);
    }

    @Override
    public boolean isSearchInLibraries() {
        return hasOption(LIBRARIES);
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        Object2IntMap<VirtualFile> roots = getRoots(file);
        if (hasOption(CONTENT)) {
            return roots.containsKey(VfsUtil.getContentRootForFile(myModule.getProject(), file));
        }
        VirtualFile root = myProjectFileIndex.getModuleSourceOrLibraryClassesRoot(file);
        return root != null && roots.containsKey(root);
    }

    private Object2IntMap<VirtualFile> getRoots(@NonNull VirtualFile file) {
//    ModelBranch branch = ModelBranch.getFileBranch(file);
//    return branch != null ? obtainBranchRoots(branch) : myRoots;
        return myRoots;
    }

//  private Object2IntMap<VirtualFile> obtainBranchRoots(ModelBranch branch) {
//    Pair<Long, Object2IntMap<VirtualFile>> pair = branch.getUserData(BRANCH_ROOTS);
//    long modCount = branch.getBranchedVfsStructureModificationCount();
//    if (pair == null || pair.first != modCount) {
//      pair = Pair.create(modCount, calcRoots(branch));
//    }
//    return pair.second;
//  }

    private static final Key<Pair<Long, Object2IntMap<VirtualFile>>> BRANCH_ROOTS =
            Key.create("BRANCH_ROOTS");

    @Override
    public int compare(@NonNull VirtualFile file1, @NonNull VirtualFile file2) {
        VirtualFile r1 = getFileRoot(file1);
        VirtualFile r2 = getFileRoot(file2);
        if (Comparing.equal(r1, r2)) {
            return 0;
        }

        if (r1 == null) {
            return -1;
        }
        if (r2 == null) {
            return 1;
        }

        Object2IntMap<VirtualFile> roots = getRoots(file1);
        int i1 = roots.getInt(r1);
        int i2 = roots.getInt(r2);
        if (i1 == 0 && i2 == 0) {
            return 0;
        }
        if (i1 > 0 && i2 > 0) {
            return i2 - i1;
        }
        return i1 > 0 ? 1 : -1;
    }

    @Nullable
    private VirtualFile getFileRoot(@NonNull VirtualFile file) {
        if (hasOption(CONTENT)) {
            return VfsUtil.getContentRootForFile(getProject(), file);
        }
        return myProjectFileIndex.getModuleSourceOrLibraryClassesRoot(file);
    }

    public Collection<VirtualFile> getRoots() {
        List<VirtualFile> result = new ArrayList<>(myRoots.keySet());
        result.sort(Comparator.comparingInt(myRoots::getInt));
        return result;
    }

    //  @Override
    public @Nullable VirtualFileEnumeration extractFileEnumeration() {
        // todo might not cheap
        if (hasOption(MODULES) || hasOption(LIBRARIES)) {
            return null;
        }

        CachedValueProvider<ConcurrentMap<Integer, VirtualFileEnumeration>> provider =
                () -> CachedValueProvider.Result.create(new ConcurrentHashMap<Integer,
                                VirtualFileEnumeration>(),
                        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS);

        CachedValuesManager cachedValuesManager =
                CachedValuesManager.getManager(myModule.getProject());
        ConcurrentMap<Integer, VirtualFileEnumeration> cacheHolder =
                cachedValuesManager.getCachedValue(myUserDataHolderBase,
                        CACHED_FILE_ID_ENUMERATIONS_KEY,
                        provider,
                        false);

        return cacheHolder.computeIfAbsent(myOptions, key -> doExtractFilIdEnumeration());
    }

    //
    @NonNull
    private VirtualFileEnumeration doExtractFilIdEnumeration() {
        IntSet result = new IntOpenHashSet();
        for (VirtualFile file : myRoots.keySet()) {
            if (file instanceof VirtualFileWithId) {
                int[] children = Arrays.stream(file.getChildren())
                        .filter(it -> it instanceof VirtualFileWithId)
                        .map(it -> (VirtualFileWithId) it)
                        .mapToInt(VirtualFileWithId::getId)
                        .toArray();
                IntArrayList integers = new IntArrayList();
                for (int child : children) {
                    integers.add(child);
                }
                result.addAll(integers);
            }
        }

        return new MyVirtualFileEnumeration(result);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ModuleWithDependenciesScope that = (ModuleWithDependenciesScope) o;
        return myOptions == that.myOptions && myModule.equals(that.myModule);
    }

    @Override
    public int calcHashCode() {
        return 31 * myModule.hashCode() + myOptions;
    }

    @Override
    public String toString() {
        return "Module-with-dependencies:" +
               myModule.getName() +
               " compile-only:" +
               hasOption(COMPILE_ONLY) +
               " include-libraries:" +
               hasOption(LIBRARIES) +
               " include-other-modules:" +
               hasOption(MODULES) +
               " include-tests:" +
               hasOption(TESTS);
    }

    private static class MyVirtualFileEnumeration implements VirtualFileEnumeration {
        private final @NonNull IntSet myResult;

        MyVirtualFileEnumeration(@NonNull IntSet result) {
            myResult = result;
        }

        @Override
        public boolean contains(int fileId) {
            return myResult.contains(fileId);
        }

        @Override
        public int[] asArray() {
            return myResult.toIntArray();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MyVirtualFileEnumeration that = (MyVirtualFileEnumeration) o;
            return myResult.equals(that.myResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(myResult);
        }

        @Override
        public String toString() {
            return Arrays.toString(myResult.toIntArray());
        }
    }
}