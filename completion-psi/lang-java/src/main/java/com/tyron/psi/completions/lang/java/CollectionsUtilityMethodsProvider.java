package com.tyron.psi.completions.lang.java;

import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_COLLECTION;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_COLLECTIONS;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_LIST;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_MAP;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_SET;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_SORTED_SET;

import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiConditionalExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportList;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;

/**
 * @author peter
 */
class CollectionsUtilityMethodsProvider {
    private final PsiElement myElement;
    private final PsiType myExpectedType;
    private final PsiType myDefaultType;
    @NotNull
    private final Consumer<? super LookupElement> myResult;

    CollectionsUtilityMethodsProvider(PsiElement position,
                                      PsiType expectedType,
                                      PsiType defaultType, @NotNull final Consumer<? super LookupElement> result) {
        myResult = result;
        myElement = position;
        myExpectedType = expectedType;
        myDefaultType = defaultType;
    }

    public void addCompletions(boolean showAll) {
        final PsiElement parent = myElement.getParent();
        if (parent instanceof PsiReferenceExpression && ((PsiReferenceExpression)parent).getQualifierExpression() != null) return;

        PsiJavaFile file = ObjectUtils.tryCast(parent.getContainingFile(), PsiJavaFile.class);
        if (file == null) return;
        final PsiClass collectionsClass =
                JavaPsiFacade.getInstance(file.getProject()).findClass(JAVA_UTIL_COLLECTIONS, file.getResolveScope());
        if (collectionsClass == null) return;
        PsiImportList importList = file.getImportList();
        if (importList != null) {
            for (PsiImportStaticStatement statement : importList.getImportStaticStatements()) {
                PsiClass aClass = statement.resolveTargetClass();
                if (aClass != null && file.getManager().areElementsEquivalent(aClass, collectionsClass)) {
                    // The Collections class is already statically imported;
                    // should be suggested anyway in JavaStaticMemberProcessor
                    return;
                }
            }
        }

        final PsiElement pparent = parent.getParent();
        if (showAll ||
                pparent instanceof PsiReturnStatement ||
                pparent instanceof PsiConditionalExpression && pparent.getParent() instanceof PsiReturnStatement) {
            addCollectionMethod(JAVA_UTIL_LIST, "emptyList", collectionsClass);
            addCollectionMethod(JAVA_UTIL_SET, "emptySet", collectionsClass);
            addCollectionMethod(JAVA_UTIL_MAP, "emptyMap", collectionsClass);
        }

        if (showAll) {
            addCollectionMethod(JAVA_UTIL_LIST, "singletonList", collectionsClass);
            addCollectionMethod(JAVA_UTIL_SET, "singleton", collectionsClass);
            addCollectionMethod(JAVA_UTIL_MAP, "singletonMap", collectionsClass);

            addCollectionMethod(JAVA_UTIL_COLLECTION, "unmodifiableCollection", collectionsClass);
            addCollectionMethod(JAVA_UTIL_LIST, "unmodifiableList", collectionsClass);
            addCollectionMethod(JAVA_UTIL_SET, "unmodifiableSet", collectionsClass);
            addCollectionMethod(JAVA_UTIL_MAP, "unmodifiableMap", collectionsClass);
            addCollectionMethod(JAVA_UTIL_SORTED_SET, "unmodifiableSortedSet", collectionsClass);
            addCollectionMethod("java.util.SortedMap", "unmodifiableSortedMap", collectionsClass);
        }

    }

    private void addCollectionMethod(final String baseClassName,
                                     @NonNls final String method, @NotNull final PsiClass collectionsClass) {
        if (isClassType(myExpectedType, baseClassName) || isClassType(myExpectedType, JAVA_UTIL_COLLECTION)) {
            addMethodItem(myExpectedType, method, collectionsClass);
        } else if (isClassType(myDefaultType, baseClassName) || isClassType(myDefaultType, JAVA_UTIL_COLLECTION)) {
            addMethodItem(myDefaultType, method, collectionsClass);
        }
    }

    private void addMethodItem(PsiType expectedType, String methodName, PsiClass containingClass) {
        final PsiMethod[] methods = containingClass.findMethodsByName(methodName, false);
        if (methods.length == 0) {
            return;
        }

        final PsiMethod method = methods[0];
       // final JavaMethodCallElement item = new JavaMethodCallElement(method, false, false);
//      //  item.setAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE);
//        item.setInferenceSubstitutorFromExpectedType(myElement, expectedType);
        myResult.consume(LookupElementBuilder.create(method.getName()));
    }

    private static boolean isClassType(final PsiType type, final String className) {
        if (type instanceof PsiClassType) {
            final PsiClass psiClass = ((PsiClassType)type).resolve();
            return psiClass != null && className.equals(psiClass.getQualifiedName());
        }
        return false;
    }

}
