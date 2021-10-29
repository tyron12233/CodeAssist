package com.tyron.psi.completions.lang.java.filter.getters;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completions.lang.java.JavaCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaCompletionUtil;
import com.tyron.psi.completions.lang.java.JavaStaticMemberProcessor;
import com.tyron.psi.completions.lang.java.TailTypes;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;
import com.tyron.psi.lookup.TailTypeDecorator;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @author peter
 */
public class JavaMembersGetter extends MembersGetter {
    private final @NotNull
    PsiType myExpectedType;
    private final CompletionParameters myParameters;

    public JavaMembersGetter(@NotNull PsiType expectedType, CompletionParameters parameters) {
        super(new JavaStaticMemberProcessor(parameters), parameters.getPosition());
        myExpectedType = JavaCompletionUtil.originalize(expectedType);
        myParameters = parameters;
    }

    public void addMembers(boolean searchInheritors, final Consumer<? super LookupElement> results) {
//        if (MagicCompletionContributor.getAllowedValues(myParameters.getPosition()) != null) {
//            return;
//        }

        addKnownConstants(results);

        addConstantsFromTargetClass(results, searchInheritors);
        if (myExpectedType instanceof PsiPrimitiveType && PsiType.DOUBLE.isAssignableFrom(myExpectedType)) {
            addConstantsFromReferencedClassesInSwitch(results);
        }

//        if (JavaCompletionContributor.IN_SWITCH_LABEL.accepts(myPlace)) {
//            return; //non-enum values are processed above, enum values will be suggested by reference completion
//        }

        final PsiClass psiClass = PsiUtil.resolveClassInType(myExpectedType);
        processMembers(results, psiClass, PsiTreeUtil.getParentOfType(myPlace, PsiAnnotation.class) == null, searchInheritors);

        if (psiClass != null && myExpectedType instanceof PsiClassType) {
           // new BuilderCompletion((PsiClassType)myExpectedType, psiClass, myPlace).suggestBuilderVariants().forEach(results::consume);
        }
    }

    private static class ConstantClass {
        final @NotNull String myConstantContainingClass;
        final @NotNull
        LanguageLevel myLanguageLevel;
        final @Nullable String myPriorityConstant;

        private ConstantClass(@NotNull String aClass,
                              @NotNull LanguageLevel level,
                              @Nullable String constant) {
            myConstantContainingClass = aClass;
            myLanguageLevel = level;
            myPriorityConstant = constant;
        }
    }

    private static final Map<String, ConstantClass> CONSTANT_SUGGESTIONS = Map.of(
            "java.nio.charset.Charset", new ConstantClass("java.nio.charset.StandardCharsets", LanguageLevel.JDK_1_7, "UTF_8"),
            "java.time.temporal.TemporalUnit", new ConstantClass("java.time.temporal.ChronoUnit", LanguageLevel.JDK_1_8, null),
            "java.time.temporal.TemporalField", new ConstantClass("java.time.temporal.ChronoField", LanguageLevel.JDK_1_8, null)
    );

    private void addKnownConstants(Consumer<? super LookupElement> results) {
        PsiFile file = myParameters.getOriginalFile();
        ConstantClass constantClass = CONSTANT_SUGGESTIONS.get(myExpectedType.getCanonicalText());
        if (constantClass != null && PsiUtil.getLanguageLevel(file).isAtLeast(constantClass.myLanguageLevel)) {
            PsiClass charsetsClass =
                    JavaPsiFacade.getInstance(file.getProject()).findClass(constantClass.myConstantContainingClass, file.getResolveScope());
            if (charsetsClass != null) {
                for (PsiField field : charsetsClass.getFields()) {
                    if (field.hasModifierProperty(PsiModifier.STATIC) &&
                            field.hasModifierProperty(PsiModifier.PUBLIC) && myExpectedType.isAssignableFrom(field.getType())) {
                        LookupElement element = createFieldElement(field);
                        if (element != null && field.getName().equals(constantClass.myPriorityConstant)) {
                           // element = PrioritizedLookupElement.withPriority(element, 1.0);
                        }
                        results.consume(element);
                    }
                }
            }
        }
    }

    private void addConstantsFromReferencedClassesInSwitch(final Consumer<? super LookupElement> results) {
       // if (!JavaCompletionContributor.IN_SWITCH_LABEL.accepts(myPlace)) return;
        PsiSwitchBlock block = Objects.requireNonNull(PsiTreeUtil.getParentOfType(myPlace, PsiSwitchBlock.class));
//        final Set<PsiField> fields = ReferenceExpressionCompletionContributor.findConstantsUsedInSwitch(block);
//        final Set<PsiClass> classes = new HashSet<>();
//        for (PsiField field : fields) {
//            ContainerUtil.addIfNotNull(classes, field.getContainingClass());
//        }
//        for (PsiClass aClass : classes) {
//            processMembers(element -> {
//                //noinspection SuspiciousMethodCalls
//                if (!fields.contains(element.getObject())) {
//                    results.consume(TailTypeDecorator.withTail(element, TailTypes.forSwitchLabel(block)));
//                }
//            }, aClass, true, false);
//        }
    }

    private void addConstantsFromTargetClass(Consumer<? super LookupElement> results, boolean searchInheritors) {
        PsiElement parent = myPlace.getParent();
        if (!(parent instanceof PsiReferenceExpression)) {
            return;
        }

        PsiElement prev = parent;
        parent = parent.getParent();
        while (parent instanceof PsiBinaryExpression) {
            final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)parent;
            final IElementType op = binaryExpression.getOperationTokenType();
            if (JavaTokenType.EQEQ == op || JavaTokenType.NE == op) {
                if (prev == binaryExpression.getROperand()) {
                    processMembers(results, getCalledClass(binaryExpression.getLOperand()), true, searchInheritors
                    );
                }
                return;
            }
            prev = parent;
            parent = parent.getParent();
        }
        if (parent instanceof PsiExpressionList) {
            processMembers(results, getCalledClass(parent.getParent()), true, searchInheritors);
        }
    }

    @Nullable
    private static PsiClass getCalledClass(@Nullable PsiElement call) {
        if (call instanceof PsiMethodCallExpression) {
            for (final JavaResolveResult result : ((PsiMethodCallExpression)call).getMethodExpression().multiResolve(true)) {
                final PsiElement element = result.getElement();
                if (element instanceof PsiMethod) {
                    final PsiClass aClass = ((PsiMethod)element).getContainingClass();
                    if (aClass != null && !CommonClassNames.JAVA_LANG_MATH.equals(aClass.getQualifiedName())) {
                        return aClass;
                    }
                }
            }
        }
        if (call instanceof PsiNewExpression) {
            final PsiJavaCodeReferenceElement reference = ((PsiNewExpression)call).getClassReference();
            if (reference != null) {
                for (final JavaResolveResult result : reference.multiResolve(true)) {
                    final PsiElement element = result.getElement();
                    if (element instanceof PsiClass) {
                        return (PsiClass)element;
                    }
                }
            }
        }
        return null;
    }

    @Override
    @Nullable
    protected LookupElement createFieldElement(PsiField field) {
        if (!myExpectedType.isAssignableFrom(field.getType())) {
            return null;
        }
        return LookupElementBuilder.create(field);
     //   return new VariableLookupItem(field, false)
//                .qualifyIfNeeded(ObjectUtils.tryCast(myParameters.getPosition().getParent(), PsiJavaCodeReferenceElement.class));
    }

    @Override
    @Nullable
    protected LookupElement createMethodElement(PsiMethod method) {
//        JavaMethodCallElement item = new JavaMethodCallElement(method, false, false);
//        item.setInferenceSubstitutorFromExpectedType(myPlace, myExpectedType);
//        PsiType type = item.getType();
//        if (type == null || !myExpectedType.isAssignableFrom(type)) {
//            return null;
//        }
        return LookupElementBuilder.create(method.getName());
//            return item;
    }
}