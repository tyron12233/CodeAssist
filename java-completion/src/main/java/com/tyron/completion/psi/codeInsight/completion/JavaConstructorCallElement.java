package com.tyron.completion.psi.codeInsight.completion;

import androidx.annotation.NonNull;

import com.tyron.completion.InsertionContext;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementDecorator;
import com.tyron.completion.lookup.LookupElementPresentation;
import com.tyron.completion.psi.completion.JavaClassNameCompletionContributor;
import com.tyron.completion.psi.completion.JavaClassNameInsertHandler;
import com.tyron.completion.psi.completion.TypedLookupItem;
import com.tyron.completion.psi.completion.item.JavaPsiClassReferenceElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class JavaConstructorCallElement extends LookupElementDecorator<LookupElement> implements TypedLookupItem {
    private static final Key<JavaConstructorCallElement> WRAPPING_CONSTRUCTOR_CALL = Key.create("WRAPPING_CONSTRUCTOR_CALL");
    @NotNull private final PsiMethod myConstructor;
    @NotNull private final PsiClassType myType;
    @NotNull private final PsiSubstitutor mySubstitutor;

    private JavaConstructorCallElement(@NotNull LookupElement classItem, @NotNull PsiMethod constructor, @NotNull PsiClassType type) {
        super(classItem);
        myConstructor = constructor;
        myType = type;
        mySubstitutor = myType.resolveGenerics().getSubstitutor();
    }

    public static List<? extends LookupElement> wrap(@NotNull JavaPsiClassReferenceElement classItem, @NotNull PsiElement position) {
        PsiClass psiClass = classItem.getObject();
        return wrap(classItem, psiClass, position, () -> JavaPsiFacade.getElementFactory(psiClass.getProject()).createType(psiClass, PsiSubstitutor.EMPTY));
    }

    public static List<? extends LookupElement> wrap(@NotNull LookupElement classItem, @NotNull PsiClass psiClass,
                                                     @NotNull PsiElement position, @NotNull Supplier<? extends PsiClassType> type) {
        if ((Registry.is("java.completion.show.constructors")) &&
            isConstructorCallPlace(position)) {
            List<PsiMethod> constructors = ContainerUtil.filter(psiClass.getConstructors(), c -> shouldSuggestConstructor(psiClass, position, c));
            if (!constructors.isEmpty()) {
                return ContainerUtil.map(constructors, c -> new JavaConstructorCallElement(classItem, c, type.get()));
            }
        }
        return Collections.singletonList(classItem);
    }

    private static boolean shouldSuggestConstructor(@NotNull PsiClass psiClass, @NotNull PsiElement position, PsiMethod constructor) {
        return JavaResolveUtil.isAccessible(constructor, psiClass, constructor.getModifierList(), position, null, null) ||
               willBeAccessibleInAnonymous(psiClass, constructor);
    }

    private static boolean willBeAccessibleInAnonymous(@NotNull PsiClass psiClass, PsiMethod constructor) {
        return !constructor.hasModifierProperty(PsiModifier.PRIVATE) && psiClass.hasModifierProperty(PsiModifier.ABSTRACT);
    }

    static boolean isConstructorCallPlace(@NotNull PsiElement position) {
        return CachedValuesManager.getCachedValue(position, () -> {
            boolean result = JavaClassNameCompletionContributor.AFTER_NEW.accepts(position) &&
                             !JavaClassNameInsertHandler.isArrayTypeExpected(PsiTreeUtil.getParentOfType(position, PsiNewExpression.class));
            return CachedValueProvider.Result.create(result, position);
        });
    }


    private void markClassItemWrapped(@NotNull LookupElement classItem) {
        LookupElement delegate = classItem;
        while (true) {
            delegate.putUserData(WRAPPING_CONSTRUCTOR_CALL, this);
            if (!(delegate instanceof LookupElementDecorator)) break;
            delegate = ((LookupElementDecorator<?>)delegate).getDelegate();
        }
    }

    @Override
    public void handleInsert(@NonNull InsertionContext context) {
        super.handleInsert(context);

        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public PsiMethod getObject() {
        return myConstructor;
    }

    @Override
    public @NotNull PsiElement getPsiElement() {
        return myConstructor;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || super.equals(o) && myConstructor.equals(((JavaConstructorCallElement)o).myConstructor);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + myConstructor.hashCode();
    }

    @NotNull
    @Override
    public PsiType getType() {
        return myType;
    }

    @Override
    public boolean isValid() {
        return myConstructor.isValid() && myType.isValid();
    }

    @Override
    public void renderElement(@NotNull LookupElementPresentation presentation) {
        super.renderElement(presentation);

        String tailText = StringUtil.notNullize(presentation.getTailText());
        int genericsEnd = tailText.lastIndexOf('>') + 1;

        presentation.clearTail();
        presentation.appendTailText(tailText.substring(0, genericsEnd), false);
        presentation.appendTailText(MemberLookupHelper.getMethodParameterString(myConstructor, mySubstitutor), false);
        presentation.appendTailText(tailText.substring(genericsEnd), true);
    }

    @NotNull
    public PsiClass getConstructedClass() {
        PsiClass aClass = myConstructor.getContainingClass();
        if (aClass == null) {
            PsiUtilCore.ensureValid(myConstructor);
            throw new AssertionError(myConstructor + " of " + myConstructor.getClass() + " returns null containing class, file=" + myConstructor.getContainingFile());
        }
        return aClass;
    }
}
