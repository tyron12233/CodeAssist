package com.tyron.completion.psi.scope;

import androidx.annotation.Nullable;

import com.tyron.common.logging.IdeLog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.codeInsight.completion.scope.JavaCompletionHints;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.JavaResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.LambdaUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCompiledElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiIntersectionType;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceType;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.PsiQualifiedReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiSuperExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.light.LightMethodBuilder;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.graphInference.FunctionalInterfaceParameterizationUtil;
import org.jetbrains.kotlin.com.intellij.psi.infos.CandidateInfo;
import org.jetbrains.kotlin.com.intellij.psi.scope.ElementClassHint;
import org.jetbrains.kotlin.com.intellij.psi.scope.JavaScopeProcessorEvent;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class JavaCompletionProcessor implements PsiScopeProcessor, ElementClassHint {

    private static final Logger LOG = IdeLog.getCurrentLogger(JavaCompletionProcessor.class);

    private final boolean myInJavaDoc;
    private boolean myStatic;
    private PsiElement myDeclarationHolder;
    private final Map<CompletionElement, CompletionElement> myResults = new LinkedHashMap<>();
    private final Set<CompletionElement> mySecondRateResults = new ReferenceOpenHashSet<>();
    private final Set<String> myShadowedNames = new HashSet<>();
    private final Set<String> myCurrentScopeMethodNames = new HashSet<>();
    private final Set<String> myFinishedScopesMethodNames = new HashSet<>();
    private final PsiElement myElement;
    private final PsiElement myScope;
    private final ElementFilter myFilter;
    private boolean myMembersFlag;
    private final boolean myQualified;
    private PsiType myQualifierType;
    private final Condition<? super String> myMatcher;
    private final Options myOptions;
    private final boolean myAllowStaticWithInstanceQualifier;
    private final NotNullLazyValue<Collection<PsiType>> myExpectedGroundTypes;

    public JavaCompletionProcessor(@NotNull PsiElement element,
                                   ElementFilter filter,
                                   Options options,
                                   @NotNull Condition<? super String> nameCondition) {
        myOptions = options;
        myElement = element;
        myMatcher = nameCondition;
        myFilter = filter;
        PsiElement scope = element;
        myInJavaDoc = JavaResolveUtil.isInJavaDoc(myElement);
        if (myInJavaDoc) myMembersFlag = true;
        while(scope != null && !(scope instanceof PsiFile) && !(scope instanceof PsiClass)){
            scope = scope.getContext();
        }
        myScope = scope;

        PsiClass qualifierClass = null;
        PsiElement elementParent = element.getContext();
        myQualified = elementParent instanceof PsiReferenceExpression && ((PsiReferenceExpression)elementParent).isQualified();
        if (elementParent instanceof PsiReferenceExpression) {
            PsiExpression qualifier = ((PsiReferenceExpression)elementParent).getQualifierExpression();
            if (qualifier instanceof PsiSuperExpression) {
                final PsiJavaCodeReferenceElement qSuper = ((PsiSuperExpression)qualifier).getQualifier();
                if (qSuper == null) {
                    qualifierClass = JavaResolveUtil.getContextClass(myElement);
                } else {
                    final PsiElement target = qSuper.resolve();
                    qualifierClass = target instanceof PsiClass ? (PsiClass)target : null;
                }
            }
            else if (qualifier != null) {
                myQualifierType = qualifier.getType();
                if (myQualifierType == null && qualifier instanceof PsiJavaCodeReferenceElement) {
                    final PsiElement target = ((PsiJavaCodeReferenceElement)qualifier).resolve();
                    if (target instanceof PsiClass) {
                        qualifierClass = (PsiClass)target;
                    }
                }
            } else {
                qualifierClass = JavaResolveUtil.getContextClass(myElement);
            }
        }
        if (qualifierClass != null) {
            myQualifierType = JavaPsiFacade
                    .getElementFactory(element.getProject()).createType(qualifierClass);
        }

        myAllowStaticWithInstanceQualifier = !options.filterStaticAfterInstance || allowStaticAfterInstanceQualifier(element);
//        myExpectedGroundTypes = NotNullLazyValue.createValue(
//                () -> ContainerUtil.map(ExpectedTypesGetter.getExpectedTypes(element, false),
//                                        FunctionalInterfaceParameterizationUtil::getGroundTargetType));
        myExpectedGroundTypes = NotNullLazyValue.createValue(Collections::emptyList);
    }

    private static boolean allowStaticAfterInstanceQualifier(@NotNull PsiElement position) {
//        return SuppressManager.getInstance().isSuppressedFor(position, AccessStaticViaInstanceBase.ACCESS_STATIC_VIA_INSTANCE) ||
//               Registry.is("ide.java.completion.suggest.static.after.instance");
        return false;
    }

    public static boolean seemsInternal(PsiClass clazz) {
        String name = clazz.getName();
        return name != null && name.startsWith("$");
    }

    @Override
    public void handleEvent(@NotNull Event event, Object associated){
        if (JavaScopeProcessorEvent.isEnteringStaticScope(event, associated)) {
            myStatic = true;
        }
        if(event == JavaScopeProcessorEvent.CHANGE_LEVEL){
            myMembersFlag = true;
            myFinishedScopesMethodNames.addAll(myCurrentScopeMethodNames);
            myCurrentScopeMethodNames.clear();
        }
        if (event == JavaScopeProcessorEvent.SET_CURRENT_FILE_CONTEXT) {
            myDeclarationHolder = (PsiElement)associated;
        }
    }

    @Override
    public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
        if (element instanceof PsiPackage && !isQualifiedContext()) {
            if (myScope instanceof PsiClass) {
                return true;
            }
            if (((PsiPackage)element).getQualifiedName().contains(".") &&
                PsiTreeUtil.getParentOfType(myElement, PsiImportStatementBase.class) != null) {
                return true;
            }
        }

        if (element instanceof PsiClass && seemsInternal((PsiClass) element)) {
            return true;
        }

        if (element instanceof PsiMember && !PsiNameHelperImpl.getInstance().isIdentifier(((PsiMember)element).getName())) {
            // The member could be defined in another JVM language where its name is not a legal name in Java.
            // In this case, just skip such the member. We cannot legally reference it from Java source.
            return true;
        }

        if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod)element;
            if (PsiTypesUtil.isGetClass(method) && PsiUtil.isLanguageLevel5OrHigher(myElement)) {
                PsiType patchedType = PsiTypesUtil.createJavaLangClassType(myElement, myQualifierType, false);
                if (patchedType != null) {
                    element = new LightMethodBuilder(element.getManager(), method.getName()).
                            addModifier(PsiModifier.PUBLIC).
                            setMethodReturnType(patchedType).
                            setContainingClass(method.getContainingClass());
                }
            }
        }

        if (element instanceof PsiVariable) {
            String name = ((PsiVariable)element).getName();
            if (myShadowedNames.contains(name)) return true;
            if (myQualified || PsiUtil.isJvmLocalVariable(element)) {
                myShadowedNames.add(name);
            }
        }

        if (element instanceof PsiMethod) {
            myCurrentScopeMethodNames.add(((PsiMethod)element).getName());
        }

        if (!satisfies(element, state) || !isAccessible(element)) return true;

        StaticProblem sp = myElement.getParent() instanceof PsiMethodReferenceExpression ? StaticProblem.none : getStaticProblem(element);
        if (sp == StaticProblem.instanceAfterStatic) return true;

        CompletionElement completion = new CompletionElement(
                element, state.get(PsiSubstitutor.KEY), getCallQualifierText(element), getMethodReferenceType(element));
        CompletionElement prev = myResults.get(completion);
        if (prev == null || completion.isMoreSpecificThan(prev)) {
            myResults.put(completion, completion);
            if (sp == StaticProblem.staticAfterInstance) {
                mySecondRateResults.add(completion);
            }
        }
        // || !PATTERNS_IN_SWITCH.isAvailable(myElement)
        if (!(element instanceof PsiClass)) return true;

        final PsiClass psiClass = (PsiClass)element;
        if (psiClass.hasModifierProperty(PsiModifier.SEALED)) {
//            addSealedHierarchy(state, psiClass);
        }
        return true;
    }

    public Iterable<CompletionElement> getResults() {
        if (mySecondRateResults.size() == myResults.size()) {
            return mySecondRateResults;
        }
        return ContainerUtil.filter(myResults.values(), element -> !mySecondRateResults.contains(element));
    }

    public void clear() {
        myResults.clear();
        mySecondRateResults.clear();
    }


    @Override
    public boolean shouldProcess(@NotNull ElementClassHint.DeclarationKind kind) {
        switch (kind) {
            case CLASS:
                return myFilter.isClassAcceptable(PsiClass.class);

            case FIELD:
                return myFilter.isClassAcceptable(PsiField.class);

            case METHOD:
                return myFilter.isClassAcceptable(PsiMethod.class);

            case PACKAGE:
                return myFilter.isClassAcceptable(PsiPackage.class);

            case VARIABLE:
                return myFilter.isClassAcceptable(PsiVariable.class);

            case ENUM_CONST:
                return myFilter.isClassAcceptable(PsiEnumConstant.class);
        }

        return false;
    }

    @Override
    public <T> T getHint(@NotNull Key<T> hintKey) {
        if (hintKey == ElementClassHint.KEY) {
            //noinspection unchecked
            return (T)this;
        }
        if (hintKey == JavaCompletionHints.NAME_FILTER) {
            //noinspection unchecked
            return (T)myMatcher;
        }

        return null;
    }

    @Nullable
    private PsiType getMethodReferenceType(PsiElement completion) {
        PsiElement parent = myElement.getParent();
        if (completion instanceof PsiMethod && parent instanceof PsiMethodReferenceExpression) {
            PsiType matchingType = ContainerUtil.find(myExpectedGroundTypes.getValue(), candidate ->
                    candidate != null && hasSuitableType((PsiMethodReferenceExpression)parent, (PsiMethod)completion, candidate));
            return matchingType != null ? matchingType : new PsiMethodReferenceType((PsiMethodReferenceExpression)parent);
        }
        return null;
    }

    private static boolean hasSuitableType(PsiMethodReferenceExpression refPlace, PsiMethod method, @NotNull PsiType expectedType) {
        PsiMethodReferenceExpression referenceExpression = createMethodReferenceExpression(method, refPlace);
        return LambdaUtil.performWithTargetType(referenceExpression, expectedType, () -> {
            JavaResolveResult result = referenceExpression.advancedResolve(false);
            return method.getManager().areElementsEquivalent(method, result.getElement()) &&
                   PsiMethodReferenceUtil
                           .isReturnTypeCompatible(referenceExpression, result, expectedType);//&&
//                   PsiMethodReferenceHighlightingUtil.checkMethodReferenceContext(referenceExpression, method, expectedType) == null;
        });
    }

    private static PsiMethodReferenceExpression createMethodReferenceExpression(PsiMethod method, PsiMethodReferenceExpression place) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(method.getProject());
        PsiMethodReferenceExpression copy = (PsiMethodReferenceExpression)place.copy();
        PsiElement referenceNameElement = copy.getReferenceNameElement();
        assert (referenceNameElement != null): copy;
        referenceNameElement.replace(method.isConstructor() ? factory.createKeyword("new") : factory.createIdentifier(method.getName()));
        return copy;
    }

    private boolean isQualifiedContext() {
        final PsiElement elementParent = myElement.getParent();
        return elementParent instanceof PsiQualifiedReference && ((PsiQualifiedReference)elementParent).getQualifier() != null;
    }

    @NotNull
    private String getCallQualifierText(@NotNull PsiElement element) {
        if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod)element;
            if (myFinishedScopesMethodNames.contains(method.getName())) {
                String className = myDeclarationHolder instanceof PsiClass ? ((PsiClass)myDeclarationHolder).getName() : null;
                if (className != null) {
                    return className + (method.hasModifierProperty(PsiModifier.STATIC) ? "." : ".this.");
                }
            }
        }
        return "";
    }

    private StaticProblem getStaticProblem(PsiElement element) {
        if (myOptions.showInstanceInStaticContext && !isQualifiedContext()) {
            return StaticProblem.none;
        }
        if (element instanceof PsiModifierListOwner) {
            PsiModifierListOwner modifierListOwner = (PsiModifierListOwner)element;
            if (myStatic) {
                if (!(element instanceof PsiClass) && !modifierListOwner.hasModifierProperty(PsiModifier.STATIC)) {
                    // we don't need non-static method in static context.
                    return StaticProblem.instanceAfterStatic;
                }
            }
            else {
                if (!myAllowStaticWithInstanceQualifier
                    && modifierListOwner.hasModifierProperty(PsiModifier.STATIC)
                    && !myMembersFlag) {
                    // according settings we don't need to process such fields/methods
                    return StaticProblem.staticAfterInstance;
                }
            }
        }
        return StaticProblem.none;
    }

    public boolean satisfies(@NotNull PsiElement element, @NotNull ResolveState state) {
        String name = PsiUtilCore.getName(element);
        if (element instanceof PsiMethod &&
            ((PsiMethod)element).isConstructor() &&
            myElement.getParent() instanceof PsiMethodReferenceExpression) {
            name = PsiKeyword.NEW;
        }
        if (name != null && StringUtil.isNotEmpty(name) && myMatcher.value(name)) {
            if (myFilter.isClassAcceptable(element.getClass()) && myFilter.isAcceptable(new CandidateInfo(element, state.get(PsiSubstitutor.KEY)), myElement)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAccessible(@Nullable final PsiElement element) {
        // if checkAccess is false, we only show inaccessible source elements because their access modifiers can be changed later by the user.
        // compiled element can't be changed, so we don't pollute the completion with them. In Javadoc, everything is allowed.
        if (!myOptions.checkAccess && myInJavaDoc) return true;

        if (isAccessibleForResolve(element)) {
            return true;
        }
        return !myOptions.checkAccess && !(element instanceof PsiCompiledElement);
    }

    private boolean isAccessibleForResolve(@Nullable PsiElement element) {
        if (element instanceof PsiMember) {
            Set<PsiClass> accessObjectClasses =
                    !myQualified ? Collections.singleton(null) :
                            myQualifierType instanceof PsiIntersectionType ?
                                    ContainerUtil.map2Set(Arrays.asList(((PsiIntersectionType) myQualifierType).getConjuncts()),
                                                          PsiUtil::resolveClassInClassTypeOnly) :
                                    Collections.singleton(PsiUtil.resolveClassInClassTypeOnly(myQualifierType));
            PsiMember member = (PsiMember)element;
            PsiResolveHelper helper = getResolveHelper();
            PsiModifierList modifierList = member.getModifierList();
            return ContainerUtil.exists(accessObjectClasses, aoc ->
                    helper.isAccessible(member, modifierList, myElement, aoc, myDeclarationHolder));
        }
        if (element instanceof PsiPackage) {
            return getResolveHelper().isAccessible((PsiPackage)element, myElement);
        }
        return true;
    }

    @NotNull
    private PsiResolveHelper getResolveHelper() {
        return JavaPsiFacade.getInstance(myElement.getProject()).getResolveHelper();
    }


    public static final class Options {
        public static final Options DEFAULT_OPTIONS = new Options(true, true, false);
        public static final Options CHECK_NOTHING = new Options(false, false, false);
        final boolean checkAccess;
        final boolean filterStaticAfterInstance;
        final boolean showInstanceInStaticContext;

        private Options(boolean checkAccess,
                        boolean filterStaticAfterInstance,
                        boolean showInstanceInStaticContext) {
            this.checkAccess = checkAccess;
            this.filterStaticAfterInstance = filterStaticAfterInstance;
            this.showInstanceInStaticContext = showInstanceInStaticContext;
        }

        public Options withCheckAccess(boolean checkAccess) {
            return new Options(checkAccess, filterStaticAfterInstance, showInstanceInStaticContext);
        }

        public Options withFilterStaticAfterInstance(boolean filterStaticAfterInstance) {
            return new Options(checkAccess, filterStaticAfterInstance, showInstanceInStaticContext);
        }

        public Options withShowInstanceInStaticContext(boolean showInstanceInStaticContext) {
            return new Options(checkAccess, filterStaticAfterInstance, showInstanceInStaticContext);
        }
    }

    private enum StaticProblem {
        none,
        staticAfterInstance,
        instanceAfterStatic
    }
}
