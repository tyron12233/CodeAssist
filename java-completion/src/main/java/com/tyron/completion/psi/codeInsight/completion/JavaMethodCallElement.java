package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.InsertionContext;
import com.tyron.completion.OffsetKey;
import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;
import com.tyron.completion.lookup.impl.DefaultLookupItemRenderer;
import com.tyron.completion.lookup.impl.LookupItem;
import com.tyron.completion.psi.completion.TypedLookupItem;
import com.tyron.completion.psi.completion.item.JavaElementLookupRenderer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;
import org.jetbrains.kotlin.com.intellij.psi.GenericsUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;
import org.jetbrains.kotlin.com.intellij.psi.PsiCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiCapturedWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiConditionalExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameterListOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.kotlin.com.intellij.util.ThreeState;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;
import java.util.Objects;

import io.github.rosemoe.sora.text.Content;

public class JavaMethodCallElement extends LookupItem<PsiMethod> implements TypedLookupItem {
    public static final Key<Boolean> COMPLETION_HINTS = Key.create("completion.hints");
    @Nullable
    private final PsiClass myContainingClass;
    private final PsiMethod myMethod;
    private final MemberLookupHelper myHelper;
    private final boolean myNegatable;
    private PsiSubstitutor myQualifierSubstitutor = PsiSubstitutor.EMPTY;
    private PsiSubstitutor myInferenceSubstitutor = PsiSubstitutor.EMPTY;
    private boolean myNeedExplicitTypeParameters;
    private String myForcedQualifier = "";
    @Nullable private String myPresentableTypeArgs;

    public JavaMethodCallElement(@NotNull PsiMethod method) {
        this(method, null);
    }

    private JavaMethodCallElement(PsiMethod method, @Nullable MemberLookupHelper helper) {
        super(method, method.isConstructor() ? "new " + method.getName() : method.getName());
        myMethod = method;
        myContainingClass = method.getContainingClass();
        myHelper = helper;
        PsiType type = method.getReturnType();
        myNegatable = type != null && PsiType.BOOLEAN.isAssignableFrom(type);
    }

    public JavaMethodCallElement(PsiMethod method, boolean shouldImportStatic, boolean mergedOverloads) {
        this(method, new MemberLookupHelper(method, method.getContainingClass(), shouldImportStatic || method.isConstructor(), mergedOverloads));
        if (!shouldImportStatic && !method.isConstructor()) {
            if (myContainingClass != null) {
                String className = myContainingClass.getName();
                if (className != null) {
                    addLookupStrings(className + "." + myMethod.getName());
                }
            }
        }
    }

    boolean isNegatable() {
        return myNegatable;
    }

    public void setForcedQualifier(@NotNull String forcedQualifier) {
        myForcedQualifier = forcedQualifier;
        setLookupString(forcedQualifier + getLookupString());
    }

    @Override
    public PsiType getType() {
        PsiType type = MemberLookupHelper.getDeclaredType(getObject(), getInferenceSubstitutor());
        return getSubstitutor().substitute(type);
    }

    public void setInferenceSubstitutorFromExpectedType(@NotNull PsiElement place, @NotNull PsiType expectedType) {
        if (myMethod.isConstructor()) {
            if (expectedType instanceof PsiClassType) {
                PsiClassType genericType = GenericsUtil.getExpectedGenericType(place, myContainingClass, (PsiClassType)expectedType);
                myQualifierSubstitutor = myInferenceSubstitutor = genericType.resolveGenerics().getSubstitutor();
            } else {
                myQualifierSubstitutor = myInferenceSubstitutor = PsiSubstitutor.EMPTY;
            }
            myNeedExplicitTypeParameters = false;
        } else {
//            myInferenceSubstitutor = SmartCompletionDecorator.calculateMethodReturnTypeSubstitutor(myMethod, expectedType);
//            myNeedExplicitTypeParameters = mayNeedTypeParameters(place) && SmartCompletionDecorator.hasUnboundTypeParams(myMethod, expectedType);
        }
        myPresentableTypeArgs = myNeedExplicitTypeParameters ? getTypeParamsText(true, myMethod, myInferenceSubstitutor) : null;
        if (myPresentableTypeArgs != null && myPresentableTypeArgs.length() > 10) {
            myPresentableTypeArgs = myPresentableTypeArgs.substring(0, 10) + "...>";
        }
    }

    public JavaMethodCallElement setQualifierSubstitutor(@NotNull PsiSubstitutor qualifierSubstitutor) {
        myQualifierSubstitutor = qualifierSubstitutor;
        return this;
    }

    @NotNull
    public PsiSubstitutor getSubstitutor() {
        return myQualifierSubstitutor;
    }

    @NotNull
    public PsiSubstitutor getInferenceSubstitutor() {
        return myInferenceSubstitutor;
    }

//    @Override
    public void setShouldBeImported(boolean shouldImportStatic) {
        myHelper.setShouldBeImported(shouldImportStatic);
    }

//    @Override
    public boolean canBeImported() {
        return myHelper != null;
    }

//    @Override
    public boolean willBeImported() {
        return canBeImported() && myHelper.willBeImported();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaMethodCallElement)) return false;
        if (!super.equals(o)) return false;
        if (!Objects.equals(myPresentableTypeArgs, ((JavaMethodCallElement)o).myPresentableTypeArgs)) return false;

        return myQualifierSubstitutor.equals(((JavaMethodCallElement)o).myQualifierSubstitutor);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (myPresentableTypeArgs == null ? 0 : myPresentableTypeArgs.hashCode());
        result = 31 * result + myQualifierSubstitutor.hashCode();
        return result;
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context) {
        final Document document = context.getDocument();
        final PsiFile file = context.getFile();
        final PsiMethod method = getObject();

        final LookupElement[] allItems = context.getElements();
        ThreeState hasParams = method.getParameterList().isEmpty() ? ThreeState.NO : ThreeState.YES;
//        ThreeState hasParams = method.getParameterList().isEmpty() ? ThreeState.NO : MethodParenthesesHandler.overloadsHaveParameters(allItems, method);
        if (method.isConstructor()) {
            PsiClass aClass = method.getContainingClass();
            if (aClass != null && aClass.getTypeParameters().length > 0) {
                document.insertString(context.getTailOffset(), "<>");
            }
        }
        JavaCompletionUtil.insertParentheses(context, this, false, hasParams, false);

        final int startOffset = context.getStartOffset();
        final OffsetKey refStart = context.trackOffset(startOffset, true);
        if (myNeedExplicitTypeParameters) {
            qualifyMethodCall(file, startOffset, document);
//            insertExplicitTypeParameters(context, refStart);
        }
        else if (myHelper != null) {
            context.commitDocument();
            importOrQualify(document, file, method, startOffset);
        }

        PsiCallExpression methodCall = findCallAtOffset(context, context.getOffset(refStart));
        // make sure this is the method call we've just added, not the enclosing one
        if (methodCall != null) {
            PsiElement completedElement = methodCall instanceof PsiMethodCallExpression ?
                    ((PsiMethodCallExpression)methodCall).getMethodExpression().getReferenceNameElement() : null;
            TextRange completedElementRange = completedElement == null ? null : completedElement.getTextRange();
            if (completedElementRange == null || completedElementRange.getStartOffset() != context.getStartOffset()) {
                methodCall = null;
            }
        }
        if (methodCall != null) {
//            CompletionMemory.registerChosenMethod(method, methodCall);
            handleNegation(context, document, methodCall);
        }

//        startArgumentLiveTemplate(context, method);
//        showParameterHints(this, context, method, methodCall);
    }

    static PsiCallExpression findCallAtOffset(InsertionContext context, int offset) {
        context.commitDocument();
        return PsiTreeUtil.findElementOfClassAtOffset(context.getFile(), offset, PsiCallExpression.class, false);
    }

    private void handleNegation(InsertionContext context, Document document, PsiCallExpression methodCall) {
        if (context.getCompletionChar() == '!' && myNegatable) {
            context.setAddCompletionChar(false);
//            FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.EXCLAMATION_FINISH);
            document.insertString(methodCall.getTextRange().getStartOffset(), "!");
        }
    }

    private void importOrQualify(Document document, PsiFile file, PsiMethod method, int startOffset) {
        if (willBeImported()) {
            if (method.isConstructor()) {
                final PsiNewExpression newExpression = PsiTreeUtil.findElementOfClassAtOffset(file, startOffset, PsiNewExpression.class, false);
                if (newExpression != null) {
                    PsiJavaCodeReferenceElement ref = newExpression.getClassReference();
                    if (ref != null && myContainingClass != null && !ref.isReferenceTo(myContainingClass)) {
                        ref.bindToElement(myContainingClass);
                        return;
                    }
                }
            } else {
                final PsiReferenceExpression ref = PsiTreeUtil.findElementOfClassAtOffset(file, startOffset, PsiReferenceExpression.class, false);
                if (ref != null && myContainingClass != null && !ref.isReferenceTo(method)) {
                    ref.bindToElementViaStaticImport(myContainingClass);
                }
                return;
            }
        }

        qualifyMethodCall(file, startOffset, document);
    }

    public static final Key<PsiMethod> ARGUMENT_TEMPLATE_ACTIVE = Key.create("ARGUMENT_TEMPLATE_ACTIVE");




    public static int getCompletionHintsLimit() {
        return Math.max(1, Registry.intValue("editor.completion.hints.per.call.limit"));
    }

    public static void setCompletionModeIfNotSet(@NotNull PsiCall expression, @NotNull Disposable disposable) {
        if (!isCompletionMode(expression)) {
            setCompletionMode(expression, true);
            Disposer.register(disposable, () -> setCompletionMode(expression, false));
        }
    }

    public static void setCompletionMode(@NotNull PsiCall expression, boolean value) {
        expression.putUserData(COMPLETION_HINTS, value ? Boolean.TRUE : null);
    }

    public static boolean isCompletionMode(@NotNull PsiCall expression) {
        return expression.getUserData(COMPLETION_HINTS) != null;
    }

    private static boolean mayNeedTypeParameters(@NotNull final PsiElement leaf) {
        if (PsiTreeUtil.getParentOfType(leaf, PsiExpressionList.class, true, PsiCodeBlock.class, PsiModifierListOwner.class) == null) {
            if (PsiTreeUtil.getParentOfType(leaf, PsiConditionalExpression.class, true, PsiCodeBlock.class, PsiModifierListOwner.class) == null) {
                return false;
            }
        }

        if (PsiUtil.getLanguageLevel(leaf).isAtLeast(LanguageLevel.JDK_1_8)) return false;

        final PsiElement parent = leaf.getParent();
        if (parent instanceof PsiReferenceExpression && ((PsiReferenceExpression)parent).getTypeParameters().length > 0) {
            return false;
        }
        return true;
    }

    private void qualifyMethodCall(PsiFile file, final int startOffset, final Document document) {
        final PsiReference reference = file.findReferenceAt(startOffset);
        if (reference instanceof PsiReferenceExpression && ((PsiReferenceExpression)reference).isQualified()) {
            return;
        }

        final PsiMethod method = getObject();
        if (method.isConstructor()) return;
        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            document.insertString(startOffset, "this.");
            return;
        }

        if (myContainingClass == null) return;

        document.insertString(startOffset, ".");
        JavaCompletionUtil.insertClassReference(myContainingClass, file, startOffset);
    }

    @Nullable
    public static String getTypeParamsText(boolean presentable, PsiTypeParameterListOwner owner, PsiSubstitutor substitutor) {
        PsiTypeParameter[] parameters = owner.getTypeParameters();
        if (parameters.length == 0) return null;

        List<PsiType> substituted = ContainerUtil.map(parameters, parameter -> {
            PsiType type = substitutor.substitute(parameter);
            if (type instanceof PsiWildcardType) type = ((PsiWildcardType)type).getExtendsBound();
            return PsiUtil.resolveClassInClassTypeOnly(type) == parameter ? null : type;
        });
        if (ContainerUtil.exists(substituted, t -> t == null || t instanceof PsiCapturedWildcardType)) return null;
        if (substituted.equals(ContainerUtil.map(parameters, TypeConversionUtil::typeParameterErasure))) return null;

        String result = "<" + StringUtil.join(substituted, presentable ? PsiType::getPresentableText : PsiType::getCanonicalText, ", ") + ">";
        return result.contains("?") ? null : result;
    }

    @Override
    public boolean isValid() {
        return super.isValid() && myInferenceSubstitutor.isValid() && getSubstitutor().isValid();
    }

    @Override
    public void renderElement(@NotNull LookupElementPresentation presentation) {
//        presentation.setIcon(DefaultLookupItemRenderer.getRawIcon(this));

        presentation.setStrikeout(JavaElementLookupRenderer.isToStrikeout(this));

        MemberLookupHelper helper = myHelper != null ? myHelper : new MemberLookupHelper(myMethod, myContainingClass, false, false);
        helper.renderElement(presentation, myHelper != null, myHelper != null && !myHelper.willBeImported(), getSubstitutor());
        if (!myForcedQualifier.isEmpty()) {
            presentation.setItemText(myForcedQualifier + presentation.getItemText());
        }

        if (myPresentableTypeArgs != null) {
            String itemText = presentation.getItemText();
            assert itemText != null;
            int i = itemText.indexOf('.');
            if (i > 0) {
                presentation.setItemText(itemText.substring(0, i + 1) + myPresentableTypeArgs + itemText.substring(i + 1));
            }
        }
    }

//    @Override
    public boolean isWorthShowingInAutoPopup() {
        // We always have method parameters
        return true;
    }

//    private static class AutoPopupCompletion extends Expression {
//        @Nullable
//        @Override
//        public Result calculateResult(ExpressionContext context) {
//            return new InvokeActionResult(() -> AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor()));
//        }
//
//        @Nullable
//        @Override
//        public Result calculateQuickResult(ExpressionContext context) {
//            return null;
//        }
//
//        @Override
//        public LookupElement @Nullable [] calculateLookupItems(ExpressionContext context) {
//            return null;
//        }
//    }
}
