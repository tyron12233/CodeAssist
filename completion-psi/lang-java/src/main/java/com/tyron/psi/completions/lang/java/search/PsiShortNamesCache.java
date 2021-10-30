package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

/**
 * Allows to retrieve files and Java classes, methods and fields in a project by non-qualified names.
 */
public abstract class PsiShortNamesCache {
    /**
     * Return the composite short names cache, uniting all short name cache instances registered via extensions.
     *
     * @param project the project to return the cache for.
     * @return the cache instance.
     */
    public static PsiShortNamesCache getInstance(Project project) {
        return project.getService(PsiShortNamesCache.class);
    }

    public static final ExtensionPointName<PsiShortNamesCache> EP_NAME = ExtensionPointName.create("com.intellij.java.shortNamesCache");

    /**
     * Returns the list of files with the specified name.
     *
     * @param name the name of the files to find.
     * @return the list of files in the project which have the specified name.
     */
    public PsiFile[] getFilesByName(@NotNull String name) {
        return PsiFile.EMPTY_ARRAY;
    }

    /**
     * Returns the list of names of all files in the project.
     *
     * @return the list of all file names in the project.
     */
    public String  [] getAllFileNames() {
        return ArrayUtilRt.EMPTY_STRING_ARRAY;
    }

    /**
     * Returns the list of all classes with the specified name in the specified scope.
     *
     * @param name  the non-qualified name of the classes to find.
     * @param scope the scope in which classes are searched.
     * @return the list of found classes.
     */
    public abstract @NotNull PsiClass  [] getClassesByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope);

    /**
     * Returns the list of names of all classes in the project and
     * (optionally) libraries.
     *
     * @return the list of all class names.
     */
    public abstract @NotNull String  [] getAllClassNames();

    public boolean processAllClassNames(@NotNull Processor<? super String> processor) {
        return ContainerUtil.process(getAllClassNames(), processor);
    }

    public boolean processAllClassNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        return ContainerUtil.process(getAllClassNames(), processor);
    }

    /**
     * Returns the list of all methods with the specified name in the specified scope.
     *
     * @param name  the name of the methods to find.
     * @param scope the scope in which methods are searched.
     * @return the list of found methods.
     */
    public abstract @NotNull PsiMethod  [] getMethodsByName(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope);

    public abstract @NotNull PsiMethod  [] getMethodsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount);

    public abstract @NotNull PsiField [] getFieldsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount);

    public abstract boolean processMethodsWithName(@NonNls @NotNull String name,
                                                   @NotNull GlobalSearchScope scope,
                                                   @NotNull Processor<? super PsiMethod> processor);

    public boolean processMethodsWithName(@NonNls @NotNull String name,
                                          @NotNull final Processor<? super PsiMethod> processor,
                                          @NotNull GlobalSearchScope scope,
                                          @Nullable IdFilter filter) {
        return processMethodsWithName(name, scope, processor);
    }

    public boolean processAllMethodNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        return ContainerUtil.process(getAllMethodNames(), processor);
    }

    public boolean processAllFieldNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        return ContainerUtil.process(getAllFieldNames(), processor);
    }

    /**
     * Returns the list of names of all methods in the project and
     * (optionally) libraries.
     *
     * @return the list of all method names.
     */
    public abstract @NotNull String  [] getAllMethodNames();

    /**
     * Returns the list of all fields with the specified name in the specified scope.
     *
     * @param name  the name of the fields to find.
     * @param scope the scope in which fields are searched.
     * @return the list of found fields.
     */
    public abstract @NotNull PsiField[] getFieldsByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope);

    /**
     * Returns the list of names of all fields in the project and
     * (optionally) libraries.
     *
     * @return the list of all field names.
     */
    public abstract @NotNull String[] getAllFieldNames();

    public boolean processFieldsWithName(@NotNull String name,
                                         @NotNull Processor<? super PsiField> processor,
                                         @NotNull GlobalSearchScope scope,
                                         @Nullable IdFilter filter) {
        return ContainerUtil.process(getFieldsByName(name, scope), processor);
    }

    public boolean processClassesWithName(@NotNull String name,
                                          @NotNull Processor<? super PsiClass> processor,
                                          @NotNull GlobalSearchScope scope,
                                          @Nullable IdFilter filter) {
        return ContainerUtil.process(getClassesByName(name, scope), processor);
    }
}