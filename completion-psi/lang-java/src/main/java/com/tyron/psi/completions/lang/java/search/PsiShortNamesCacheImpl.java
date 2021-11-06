package com.tyron.psi.completions.lang.java.search;

import com.tyron.psi.completions.lang.java.stubs.JavaShortClassNameIndex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;
import org.jetbrains.kotlin.com.intellij.util.CommonProcessors;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.SmartList;

import java.util.*;

public class PsiShortNamesCacheImpl extends PsiShortNamesCache {
    @NotNull
    private final Project myProject;

    public PsiShortNamesCacheImpl(@NotNull Project project) {
        myProject = project;
    }

    @Override
    public @NotNull PsiFile[] getFilesByName(@NotNull String name) {
        return PsiFile.EMPTY_ARRAY;
        //return FilenameIndex.getFilesByName(myProject, name, GlobalSearchScope.projectScope(myProject));
    }

    @Override
    public @NotNull String[] getAllFileNames() {
        return new String[0];
        //return FilenameIndex.getAllFilenames(myProject);
    }

    @Override
    public @NotNull PsiClass[] getClassesByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        Collection<PsiClass> classes = JavaShortClassNameIndex.getInstance().get(name, myProject, scope);
        if (classes.isEmpty()) return PsiClass.EMPTY_ARRAY;

        List<PsiClass> result = new ArrayList<>(classes.size());
        Map<String, List<PsiClass>> uniqueQName2Classes = new HashMap<>(classes.size());
        Set<PsiClass> hiddenClassesToRemove = null;

        OuterLoop:
        for (PsiClass aClass : classes) {
            VirtualFile vFile = aClass.getContainingFile().getVirtualFile();
            if (!scope.contains(vFile)) continue;

            String qName = aClass.getQualifiedName();
            if (qName != null) {
                List<PsiClass> previousQNamedClasses = uniqueQName2Classes.get(qName);
                List<PsiClass> qNamedClasses = new SmartList<>();

                if (previousQNamedClasses != null) {
                    for (PsiClass previousClass : previousQNamedClasses) {
                        VirtualFile previousClassVFile = previousClass.getContainingFile().getVirtualFile();
                        int res = scope.compare(previousClassVFile, vFile);
                        if (res > 0) {
                            continue OuterLoop; // previousClass hides aClass in classpath, so skip adding aClass
                        }
                        else if (res < 0) {
                            // aClass hides previousClass in classpath, so remove it from list later
                            if (hiddenClassesToRemove == null) {
                                hiddenClassesToRemove = new HashSet<>();
                            }
                            hiddenClassesToRemove.add(previousClass);
                            qNamedClasses.add(aClass);
                        }
                        else {
                            qNamedClasses.add(aClass);
                        }
                    }
                }
                else {
                    qNamedClasses.add(aClass);
                }

                uniqueQName2Classes.put(qName, qNamedClasses);
            }

            result.add(aClass);
        }

        if (hiddenClassesToRemove != null) result.removeAll(hiddenClassesToRemove);

        return result.toArray(PsiClass.EMPTY_ARRAY);
    }

    @Override
    public @NotNull String[] getAllClassNames() {
        return new String[0];
//        return ArrayUtilRt.toStringArray(JavaShortClassNameIndex.getInstance().getAllKeys(myProject));
    }

    @Override
    public boolean processAllClassNames(@NotNull Processor<? super String> processor) {
        return false;
//        return JavaShortClassNameIndex.getInstance().processAllKeys(myProject, processor);
    }

    @Override
    public boolean processAllClassNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, IdFilter filter) {
        return false;
        //return StubIndex.getInstance().processAllKeys(JavaStubIndexKeys.CLASS_SHORT_NAMES, processor, scope, filter);
    }

    @Override
    public boolean processAllMethodNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, IdFilter filter) {
        return false;
        //return StubIndex.getInstance().processAllKeys(JavaStubIndexKeys.METHODS, processor, scope, filter);
    }

    @Override
    public boolean processAllFieldNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, IdFilter filter) {
        return false;
        //return StubIndex.getInstance().processAllKeys(JavaStubIndexKeys.FIELDS, processor, scope, filter);
    }

    @Override
    public @NotNull PsiMethod[] getMethodsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        return PsiMethod.EMPTY_ARRAY;
//        Collection<PsiMethod> methods = JavaMethodNameIndex.getInstance().get(name, myProject, scope);
//        return filterMembers(methods, scope, PsiMethod.EMPTY_ARRAY);
    }

    @Override
    public @NotNull PsiMethod[] getMethodsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
        return PsiMethod.EMPTY_ARRAY;
//        List<PsiMethod> methods = new SmartList<>();
//        Processor<PsiMethod> processor = new CommonProcessors.CollectProcessor<PsiMethod>(methods) {
//            @Override
//            public boolean process(PsiMethod method) {
//                return methods.size() != maxCount && super.process(method);
//            }
//        };
//        StubIndex.getInstance().processElements(JavaStubIndexKeys.METHODS, name, myProject, scope, PsiMethod.class, processor);
//        return filterMembers(methods, scope, PsiMethod.EMPTY_ARRAY);
    }

    @Override
    public boolean processMethodsWithName(@NotNull String name, @NotNull GlobalSearchScope scope, @NotNull Processor<? super PsiMethod> processor) {
        return false;
       // return StubIndex.getInstance().processElements(JavaStubIndexKeys.METHODS, name, myProject, scope, PsiMethod.class, processor);
    }

    @Override
    public @NotNull String[] getAllMethodNames() {
        return new String[0];
        //return ArrayUtilRt.toStringArray(JavaMethodNameIndex.getInstance().getAllKeys(myProject));
    }

    @Override
    public @NotNull PsiField[] getFieldsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
        return PsiField.EMPTY_ARRAY;
//        List<PsiField> fields = new SmartList<>();
//        Processor<PsiField> processor = new CommonProcessors.CollectProcessor<>(fields) {
//            @Override
//            public boolean process(PsiField method) {
//                return fields.size() != maxCount && super.process(method);
//            }
//        };
//        StubIndex.getInstance().processElements(JavaStubIndexKeys.FIELDS, name, myProject, scope, PsiField.class, processor);
//        return filterMembers(fields, scope, PsiField.EMPTY_ARRAY);
    }

    @Override
    public @NotNull PsiField[] getFieldsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        return PsiField.EMPTY_ARRAY;
//        Collection<PsiField> fields = JavaFieldNameIndex.getInstance().get(name, myProject, scope);
//        return filterMembers(fields, scope, PsiField.EMPTY_ARRAY);
    }

    @Override
    public @NotNull String[] getAllFieldNames() {
        return new String[0];
//        return ArrayUtilRt.toStringArray(JavaFieldNameIndex.getInstance().getAllKeys(myProject));
    }

    @Override
    public boolean processFieldsWithName(@NotNull String name,
                                         @NotNull Processor<? super PsiField> processor,
                                         @NotNull GlobalSearchScope scope,
                                         @Nullable IdFilter filter) {
        return false;
//        return StubIndex.getInstance().processElements(
//                JavaStubIndexKeys.FIELDS, name, myProject, new JavaSourceFilterScope(scope), filter, PsiField.class, processor);
    }

    @Override
    public boolean processMethodsWithName(@NotNull String name,
                                          @NotNull Processor<? super PsiMethod> processor,
                                          @NotNull GlobalSearchScope scope,
                                          @Nullable IdFilter filter) {
        return false;
//        return StubIndex.getInstance().processElements(
//                JavaStubIndexKeys.METHODS, name, myProject, new JavaSourceFilterScope(scope), filter, PsiMethod.class, processor);
    }

    @Override
    public boolean processClassesWithName(@NotNull String name,
                                          @NotNull Processor<? super PsiClass> processor,
                                          @NotNull GlobalSearchScope scope,
                                          @Nullable IdFilter filter) {
        return false;
//        return StubIndex.getInstance().processElements(
//                JavaStubIndexKeys.CLASS_SHORT_NAMES, name, myProject, new JavaSourceFilterScope(scope), filter, PsiClass.class, processor);
    }

    private <T extends PsiMember> T[] filterMembers(@NotNull Collection<? extends T> members, @NotNull GlobalSearchScope scope, T [] emptyArray) {
        return emptyArray;
//        if (members.isEmpty()) {
//            return emptyArray;
//        }
//
//        PsiManager myManager = PsiManager.getInstance(myProject);
//        Set<PsiMember> set = CollectionFactory.createCustomHashingStrategySet(new HashingStrategy<>() {
//            @Override
//            public int hashCode(PsiMember member) {
//                int code = 0;
//                final PsiClass clazz = member.getContainingClass();
//                if (clazz != null) {
//                    String name = clazz.getName();
//                    if (name != null) {
//                        code += name.hashCode();
//                    }
//                    else {
//                        //anonymous classes are not equivalent
//                        code += clazz.hashCode();
//                    }
//                }
//                if (member instanceof PsiMethod) {
//                    code += 37 * ((PsiMethod)member).getParameterList().getParametersCount();
//                }
//                return code;
//            }
//
//            @Override
//            public boolean equals(PsiMember object, PsiMember object1) {
//                return myManager.areElementsEquivalent(object, object1);
//            }
//        });
//
//        List<T> result = new ArrayList<>(members.size());
//        for (T member : members) {
//            ProgressIndicatorProvider.checkCanceled();
//            if (scope.contains(member.getContainingFile().getVirtualFile()) && set.add(member)) {
//                result.add(member);
//            }
//        }
//        return result.toArray(emptyArray);
    }
}
