package org.jetbrains.kotlin.com.intellij.psi.impl.file;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.google.common.collect.Iterables;
import org.jetbrains.kotlin.com.intellij.core.CoreJavaFileManager;
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleWithDependenciesScope;
import org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes.JdkScope;
import org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes.LibraryScopeBase;
import org.jetbrains.kotlin.com.intellij.openapi.roots.PackageIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaModule;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaFullClassNameIndex;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.impl.VirtualFileEnumeration;
import org.jetbrains.kotlin.com.intellij.util.Query;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

public class JavaFileManagerImpl implements JavaFileManager {


    private final PsiManager psiManager;

    private final Map<String, List<Pair<PsiClass, VirtualFile>>> psiClassMap = new WeakHashMap<>();

    public JavaFileManagerImpl(PsiManager psiManager) {
        this.psiManager = psiManager;
    }

    @Nullable
    @Override
    public PsiPackage findPackage(@NonNull String packageName) {
        Query<VirtualFile> query = PackageIndex.getInstance(psiManager.getProject())
                .getDirsByPackageName(packageName, true);
        if (query.findFirst() == null) {
            return null;
        }
        return new PsiPackageImpl(psiManager, packageName);
    }

    @NonNull
    @Override
    public PsiClass[] findClasses(@NonNull String qName, @NonNull GlobalSearchScope scope) {
        List<Pair<PsiClass, VirtualFile>> result = doFindClasses(qName, scope);

        int count = result.size();
        if (count == 0) {
            return PsiClass.EMPTY_ARRAY;
        }
        if (count == 1) {
            return new PsiClass[]{result.get(0).getFirst()};
        }

        ContainerUtil.quickSort(result, (o1, o2) -> scope.compare(o2.getSecond(), o1.getSecond()));

        return result.stream().map(p -> p.getFirst()).toArray(PsiClass[]::new);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private List<Pair<PsiClass, VirtualFile>> doFindClasses(@NonNull String qName,
                                                            @NonNull GlobalSearchScope scope) {
        Collection<PsiClass> classes = JavaFullClassNameIndex.getInstance()
                .get(qName.hashCode(), psiManager.getProject(), scope);

        List<Pair<PsiClass, VirtualFile>> result = new ArrayList<>(classes.size());
        for (PsiClass aClass : classes) {
            PsiFile file = aClass.getContainingFile();
            if (file == null) {
                throw new AssertionError("No file for class: " + aClass + " of " + aClass.getClass());
            }
            VirtualFile vFile = file.getViewProvider().getVirtualFile();
            if (!hasAcceptablePackage(vFile)) continue;

            result.add(Pair.create(aClass, vFile));
        }

        return result;
    }

    private boolean hasAcceptablePackage(@NotNull VirtualFile vFile) {
        if (FileTypeRegistry.getInstance().isFileOfType(vFile, JavaClassFileType.INSTANCE)) {
            // See IDEADEV-5626
            VirtualFile root = ProjectRootManager.getInstance(psiManager.getProject()).getFileIndex().getClassRootForFile(vFile);
            VirtualFile parent = vFile.getParent();
            PsiNameHelper nameHelper = PsiNameHelper.getInstance(psiManager.getProject());
            while (parent != null && !Comparing.equal(parent, root)) {
                if (!nameHelper.isIdentifier(parent.getName())) return false;
                parent = parent.getParent();
            }
        }

        return true;
    }

    @Nullable
    @Override
    public PsiClass findClass(@NonNull String qName, @NonNull GlobalSearchScope scope) {
        VirtualFile bestFile = null;
        PsiClass bestClass = null;
        List<Pair<PsiClass, VirtualFile>> result = doFindClasses(qName, scope);

        for (int i = 0; i < result.size(); i++) {
            Pair<PsiClass, VirtualFile> pair = result.get(i);
            VirtualFile vFile = pair.getSecond();
            if (bestFile == null || scope.compare(vFile, bestFile) > 0) {
                bestFile = vFile;
                bestClass = pair.getFirst();
            }
        }

        return bestClass;
    }

    @Override
    public @NonNull Collection<String> getNonTrivialPackagePrefixes() {
        return Collections.emptyList();
    }

    @Override
    public @NonNull Collection<PsiJavaModule> findModules(@NonNull String s,
                                                          @NonNull GlobalSearchScope globalSearchScope) {
        return Collections.emptyList();
    }

    /**
     * For some reason the scopes field in a UnionScope is not exposed by a getter.
     * This is a hack to get the individual scopes inside a union scope.
     */
    private static final class UnionScopeHack {

        private static final Class<?> UNION_SCOPE_CLASS;
        private static final Field SCOPES_FIELD;

        static {
            try {
                UNION_SCOPE_CLASS =
                        Class.forName(GlobalSearchScope.class.getName() + "$UnionScope");

                SCOPES_FIELD = UNION_SCOPE_CLASS.getDeclaredField("myScopes");
                SCOPES_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        public static List<GlobalSearchScope> getScopes(GlobalSearchScope unionScope) {
            Object o;
            try {
                o = SCOPES_FIELD.get(unionScope);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            if (o == null) {
                return Collections.emptyList();
            }

            GlobalSearchScope[] scopes = (GlobalSearchScope[]) o;
            return Arrays.asList(scopes);
        }
    }
}
