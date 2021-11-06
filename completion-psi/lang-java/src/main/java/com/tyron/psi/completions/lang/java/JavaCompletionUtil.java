package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.JavaSmartCompletionContributor.getExpectedTypes;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionUtil;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.completions.lang.java.guess.GuessManager;
import com.tyron.psi.completions.lang.java.lookup.PsiTypeLookupItem;
import com.tyron.psi.completions.lang.java.lookup.TypedLookupItem;
import com.tyron.psi.completions.lang.java.scope.CompletionElement;
import com.tyron.psi.completions.lang.java.scope.JavaCompletionProcessor;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;
import com.tyron.psi.util.DocumentUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.RangeMarkerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.Conditions;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.light.LightVariableBuilder;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiImmediateClassType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiJavaModuleReferenceImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.PairFunction;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.JBIterable;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaCompletionUtil {

    private static final Logger LOG = Logger.getInstance(JavaCompletionUtil.class);
    public static final Key<Boolean> FORCE_SHOW_SIGNATURE_ATTR = Key.create("forceShowSignature");
    public static final Key<PairFunction<PsiExpression, CompletionParameters, PsiType>> DYNAMIC_TYPE_EVALUATOR = Key.create("DYNAMIC_TYPE_EVALUATOR");
    private static final Key<PsiType> QUALIFIER_TYPE_ATTR = Key.create("qualifierType"); // SmartPsiElementPointer to PsiType of "qualifier"

    @NotNull
    public static String escapeXmlIfNeeded(InsertionContext context, @NotNull String generics) {
        if (context.getFile().getViewProvider().getBaseLanguage().getClass().getName().contains("JspxLanguage")) {
            return StringUtil.escapeXmlEntities(generics);
        }
        return generics;
    }

    //need to shorten references in type argument list
    public static void shortenReference(final PsiFile file, final int offset) throws IncorrectOperationException {
        Project project = file.getProject();
        final PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
        Document document = manager.getDocument(file);
        if (document == null) {
            PsiUtilCore.ensureValid(file);
            LOG.error("No document for " + file);
            return;
        }

        manager.commitDocument(document);
        PsiReference ref = file.findReferenceAt(offset);
        if (ref != null) {
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(ref.getElement());
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document);
        }
    }

    public static void insertClassReference(@NotNull PsiClass psiClass, @NotNull PsiFile file, int offset) {
        insertClassReference(psiClass, file, offset, offset);
    }

    public static int insertClassReference(PsiClass psiClass, PsiFile file, int startOffset, int endOffset) {
        final Project project = file.getProject();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitAllDocuments();

        final PsiManager manager = file.getManager();

        final Document document = FileDocumentManager.getInstance().getDocument(file.getViewProvider().getVirtualFile());

        PsiReference reference = file.findReferenceAt(startOffset);
        if (reference != null && manager.areElementsEquivalent(psiClass, reference.resolve())) {
            return endOffset;
        }

        String name = psiClass.getName();
        if (name == null) {
            return endOffset;
        }

        if (reference != null && !psiClass.hasModifierProperty(PsiModifier.STATIC)) {
            PsiClass containingClass = psiClass.getContainingClass();
            if (containingClass != null && containingClass.hasTypeParameters()) {
                PsiModifierListOwner enclosingStaticElement = PsiUtil.getEnclosingStaticElement(reference.getElement(), null);
                if (enclosingStaticElement != null && !PsiTreeUtil.isAncestor(enclosingStaticElement, psiClass, false)) {
                    return endOffset;
                }
            }
        }

        assert document != null;
        document.replaceString(startOffset, endOffset, name);

        int newEndOffset = startOffset + name.length();
        final RangeMarker toDelete = insertTemporary(newEndOffset, document, " ");

        documentManager.commitAllDocuments();

        PsiElement element = file.findElementAt(startOffset);
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            if (parent instanceof PsiJavaCodeReferenceElement &&
                    !((PsiJavaCodeReferenceElement)parent).isQualified() &&
                    !(parent.getParent() instanceof PsiPackageStatement)) {
                PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement)parent;

                if (psiClass.isValid() && !psiClass.getManager().areElementsEquivalent(psiClass, resolveReference(ref))) {
                    final boolean staticImport = ref instanceof PsiImportStaticReferenceElement;
                    PsiElement newElement;
                    try {
                        newElement = staticImport
                                ? ((PsiImportStaticReferenceElement)ref).bindToTargetClass(psiClass)
                                : ref.bindToElement(psiClass);
                    }
                    catch (IncorrectOperationException e) {
                        return endOffset; // can happen if fqn contains reserved words, for example
                    }

                    final RangeMarker rangeMarker = null; //document.createRangeMarker(newElement.getTextRange());
                    documentManager.doPostponedOperationsAndUnblockDocument(document);
                    documentManager.commitDocument(document);

                    newElement = null;// CodeInsightUtilCore.findElementInRange(file, rangeMarker.getStartOffset(), rangeMarker.getEndOffset(),
//                            PsiJavaCodeReferenceElement.class,
//                            JavaLanguage.INSTANCE);
                    //rangeMarker.dispose();
                    if (newElement != null) {
                        newEndOffset = newElement.getTextRange().getEndOffset();
                        if (!(newElement instanceof PsiReferenceExpression)) {
                            PsiReferenceParameterList parameterList = ((PsiJavaCodeReferenceElement)newElement).getParameterList();
                            if (parameterList != null) {
                                newEndOffset = parameterList.getTextRange().getStartOffset();
                            }
                        }

                        if (!staticImport &&
                                !psiClass.getManager().areElementsEquivalent(psiClass, resolveReference((PsiReference)newElement)) &&
                                !PsiUtil.isInnerClass(psiClass)) {
                            final String qName = psiClass.getQualifiedName();
                            if (qName != null) {
                                document.replaceString(newElement.getTextRange().getStartOffset(), newEndOffset, qName);
                                newEndOffset = newElement.getTextRange().getStartOffset() + qName.length();
                            }
                        }
                    }
                }
            }
        }

        if (toDelete != null && toDelete.isValid()) {
            DocumentUtils.deleteString(document, toDelete.getStartOffset(), toDelete.getEndOffset());
        }

        return newEndOffset;
    }

    public static boolean inSomePackage(PsiElement context) {
        PsiFile contextFile = context.getContainingFile();
        return contextFile instanceof PsiClassOwner && StringUtil.isNotEmpty(((PsiClassOwner)contextFile).getPackageName());
    }

    @Nullable
    public static PsiType getLookupElementType(final LookupElement element) {
        TypedLookupItem typed = element.as(TypedLookupItem.CLASS_CONDITION_KEY);
        return typed != null ? typed.getType() : null;
    }

    @NotNull
    public static <T extends PsiType> T originalize(@NotNull T type) {
        if (!type.isValid()) {
            return type;
        }

        T result = new PsiTypeMapper() {
            private final Set<PsiClassType> myVisited = new ReferenceOpenHashSet<>();

            @Override
            public PsiType visitClassType(@NotNull final PsiClassType classType) {
                if (!myVisited.add(classType)) return classType;

                final PsiClassType.ClassResolveResult classResolveResult = classType.resolveGenerics();
                final PsiClass psiClass = classResolveResult.getElement();
                final PsiSubstitutor substitutor = classResolveResult.getSubstitutor();
                if (psiClass == null) return classType;

                return new PsiImmediateClassType(CompletionUtil.getOriginalOrSelf(psiClass), originalizeSubstitutor(substitutor));
            }

            private PsiSubstitutor originalizeSubstitutor(final PsiSubstitutor substitutor) {
                PsiSubstitutor originalSubstitutor = PsiSubstitutor.EMPTY;
                for (final Map.Entry<PsiTypeParameter, PsiType> entry : substitutor.getSubstitutionMap().entrySet()) {
                    final PsiType value = entry.getValue();
                    originalSubstitutor = originalSubstitutor.put(CompletionUtil.getOriginalOrSelf(entry.getKey()),
                            value == null ? null : mapType(value));
                }
                return originalSubstitutor;
            }


            @Override
            public PsiType visitType(@NotNull PsiType type) {
                return type;
            }
        }.mapType(type);
        if (result == null) {
            throw new AssertionError("Null result for type " + type + " of class " + type.getClass());
        }
        return result;
    }

    public static boolean isInExcludedPackage(@NotNull final PsiMember member, boolean allowInstanceInnerClasses) {
        final String name = PsiUtil.getMemberQualifiedName(member);
        if (name == null) return false;

        if (!member.hasModifierProperty(PsiModifier.STATIC)) {
            if (member instanceof PsiMethod || member instanceof PsiField) {
                return false;
            }
            if (allowInstanceInnerClasses && member instanceof PsiClass && member.getContainingClass() != null) {
                return false;
            }
        }

        return false;
//        return JavaProjectCodeInsightSettings.getSettings(member.getProject()).isExcluded(name);
    }

    public static LinkedHashSet<String> getAllLookupStrings(@NotNull PsiMember member) {
        LinkedHashSet<String> allLookupStrings = new LinkedHashSet<>();
        String name = member.getName();
        allLookupStrings.add(name);
        PsiClass containingClass = member.getContainingClass();
        while (containingClass != null) {
            final String className = containingClass.getName();
            if (className == null) {
                break;
            }
            name = className + "." + name;
            allLookupStrings.add(name);
            final PsiElement parent = containingClass.getParent();
            if (!(parent instanceof PsiClass)) {
                break;
            }
            containingClass = (PsiClass)parent;
        }
        return allLookupStrings;
    }

    @Nullable
    public static RangeMarker insertTemporary(int endOffset, Document document, String temporary) {
        final CharSequence chars = document.getCharsSequence();
        if (endOffset < chars.length() && Character.isJavaIdentifierPart(chars.charAt(endOffset))){
            DocumentUtils.insertString(document, endOffset, temporary);
//            RangeMarkerImpl impl = new RangeMarkerImpl();
//            RangeMarker toDelete = document.createRangeMarker(endOffset, endOffset + 1);
//            toDelete.setGreedyToLeft(true);
//            toDelete.setGreedyToRight(true);
//            return toDelete;
        }
        return null;
        //throw new UnsupportedOperationException("Not yet implemented, inserTemporary()");
    }

    static Set<LookupElement> processJavaReference(PsiElement element,
                                                   PsiJavaCodeReferenceElement javaReference,
                                                   ElementFilter elementFilter,
                                                   JavaCompletionProcessor.Options options,
                                                   Condition<? super String> nameCondition,
                                                   CompletionParameters parameters) {
        PsiElement elementParent = element.getContext();
        if (elementParent instanceof PsiReferenceExpression) {
            final PsiExpression qualifierExpression = ((PsiReferenceExpression)elementParent).getQualifierExpression();
            if (qualifierExpression instanceof PsiReferenceExpression) {
                final PsiElement resolve = ((PsiReferenceExpression)qualifierExpression).resolve();
                if (resolve instanceof PsiParameter) {
                    final PsiElement declarationScope = ((PsiParameter)resolve).getDeclarationScope();
                    if (((PsiParameter)resolve).getType() instanceof PsiLambdaParameterType) {
                        final PsiLambdaExpression lambdaExpression = (PsiLambdaExpression)declarationScope;
                        if (PsiTypesUtil.getExpectedTypeByParent(lambdaExpression) == null) {
                            final int parameterIndex = lambdaExpression.getParameterList().getParameterIndex((PsiParameter)resolve);
                            final Set<LookupElement> set = new LinkedHashSet<>();
                            final boolean overloadsFound = LambdaUtil.processParentOverloads(lambdaExpression, functionalInterfaceType -> {
                                PsiType qualifierType = LambdaUtil.getLambdaParameterFromType(functionalInterfaceType, parameterIndex);
                                if (qualifierType instanceof PsiWildcardType) {
                                    qualifierType = ((PsiWildcardType)qualifierType).getBound();
                                }
                                if (qualifierType == null) return;

                                PsiReferenceExpression fakeRef = createReference("xxx.xxx", createContextWithXxxVariable(element, qualifierType));
                                set.addAll(processJavaQualifiedReference(fakeRef.getReferenceNameElement(), fakeRef, elementFilter, options, nameCondition, parameters));
                            });
                            if (overloadsFound) return set;
                        }
                    }
                }
            }
        }
        return processJavaQualifiedReference(element, javaReference, elementFilter, options, nameCondition, parameters);
    }

    private static Set<LookupElement> processJavaQualifiedReference(PsiElement element,
                                                                    PsiJavaCodeReferenceElement javaReference,
                                                                    ElementFilter elementFilter,
                                                                    JavaCompletionProcessor.Options options,
                                                                    Condition<? super String> nameCondition,
                                                                    CompletionParameters parameters) {
        final Set<LookupElement> set = new LinkedHashSet<>();

        final JavaCompletionProcessor processor = new JavaCompletionProcessor(element, elementFilter, options, nameCondition);
        final PsiType plainQualifier = processor.getQualifierType();

        List<PsiType> runtimeQualifiers = getQualifierCastTypes(javaReference, parameters);
        if (!runtimeQualifiers.isEmpty()) {
            PsiType[] conjuncts = JBIterable.of(plainQualifier).append(runtimeQualifiers).toArray(PsiType.EMPTY_ARRAY);
            PsiType composite = PsiIntersectionType.createIntersection(false, conjuncts);
            PsiElement ctx = createContextWithXxxVariable(element, composite);
            javaReference = createReference("xxx.xxx", ctx);
            processor.setQualifierType(composite);
        }

        javaReference.processVariants(processor);


        List<PsiTypeLookupItem> castItems = ContainerUtil.map(runtimeQualifiers, q -> PsiTypeLookupItem.createLookupItem(q, element));

        final boolean pkgContext = inSomePackage(element);

        PsiClass qualifierClass = PsiUtil.resolveClassInClassTypeOnly(plainQualifier);
        final boolean honorExcludes = qualifierClass == null || !isInExcludedPackage(qualifierClass, false);

        Object info = ObjectUtils.coalesce(getExpectedTypes(parameters), Collections.emptySet());
        Set<PsiType> expectedTypes = new HashSet<>();
        if (info instanceof ExpectedTypeInfo[]) {
            ExpectedTypeInfo[] expectedTypeInfo = (ExpectedTypeInfo[]) info;
            for (ExpectedTypeInfo typeInfo : expectedTypeInfo) {
                expectedTypes.add(typeInfo.getType());
            }
        }
        final Set<PsiMember> mentioned = new HashSet<>();
        for (CompletionElement completionElement : processor.getResults()) {
            for (LookupElement item : createLookupElements(completionElement, javaReference)) {
                item.putUserData(QUALIFIER_TYPE_ATTR, plainQualifier);
                final Object o = item.getObject();
                if (o instanceof PsiClass) {
                    PsiClass specifiedQualifierClass = javaReference.isQualified() ? qualifierClass : ((PsiClass)o).getContainingClass();
                    if (!isSourceLevelAccessible(element, (PsiClass)o, pkgContext, specifiedQualifierClass)) {
                        continue;
                    }
                }
                if (o instanceof PsiMember) {
                    if (honorExcludes && isInExcludedPackage((PsiMember)o, true)) {
                        continue;
                    }
                    mentioned.add(CompletionUtil.getOriginalOrSelf((PsiMember)o));
                }
                set.add(item);
//                set.add()
//                PsiTypeLookupItem qualifierCast = findQualifierCast(item, castItems, plainQualifier, processor, expectedTypes);
//                if (qualifierCast != null) item = castQualifier(item, qualifierCast);
//                set.add(highlightIfNeeded(qualifierCast != null ? qualifierCast.getType() : plainQualifier, item, o, element));
            }
        }

        PsiElement refQualifier = javaReference.getQualifier();
        if (refQualifier == null && PsiTreeUtil.getParentOfType(element, PsiPackageStatement.class, PsiImportStatementBase.class) == null) {
            final StaticMemberProcessor memberProcessor = new JavaStaticMemberProcessor(parameters);
            memberProcessor.processMembersOfRegisteredClasses(nameCondition, (member, psiClass) -> {
                if (!mentioned.contains(member) && processor.satisfies(member, ResolveState.initial())) {
                    ContainerUtil.addIfNotNull(set, memberProcessor.createLookupElement(member, psiClass, true));
                }
            });
        }
        else if (refQualifier instanceof PsiSuperExpression && ((PsiSuperExpression)refQualifier).getQualifier() == null) {
           // set.addAll(SuperCalls.suggestQualifyingSuperCalls(element, javaReference, elementFilter, options, nameCondition));
        }

        return set;
    }

    static Iterable<? extends LookupElement> createLookupElements(CompletionElement completionElement, PsiJavaReference reference) {
        Object completion = completionElement.getElement();
        assert !(completion instanceof LookupElement);

        if (reference instanceof PsiJavaCodeReferenceElement) {
            if (completion instanceof PsiMethod &&
                    ((PsiJavaCodeReferenceElement)reference).getParent() instanceof PsiImportStaticStatement) {
                return Collections.singletonList(JavaLookupElementBuilder.forMethod((PsiMethod)completion, PsiSubstitutor.EMPTY));
            }
//
//            if (completion instanceof PsiClass) {
//                List<JavaPsiClassReferenceElement> classItems = JavaClassNameCompletionContributor.createClassLookupItems(
//                        CompletionUtil.getOriginalOrSelf((PsiClass)completion),
//                        JavaClassNameCompletionContributor.AFTER_NEW.accepts(reference),
//                        JavaClassNameInsertHandler.JAVA_CLASS_INSERT_HANDLER,
//                        Conditions.alwaysTrue());
//                return JBIterable.from(classItems).flatMap(i -> JavaConstructorCallElement.wrap(i, reference.getElement()));
//            }
        }

        PsiSubstitutor substitutor = completionElement.getSubstitutor();
        if (substitutor == null) substitutor = PsiSubstitutor.EMPTY;
//        if (completion instanceof PsiClass) {
//            JavaPsiClassReferenceElement classItem =
//                    JavaClassNameCompletionContributor.createClassLookupItem((PsiClass)completion, true).setSubstitutor(substitutor);
//            return JavaConstructorCallElement.wrap(classItem, reference.getElement());
//        }
//        if (completion instanceof PsiMethod) {
//            if (reference instanceof PsiMethodReferenceExpression) {
//                return Collections.singleton((LookupElement)new JavaMethodReferenceElement(
//                        (PsiMethod)completion, (PsiMethodReferenceExpression)reference, completionElement.getMethodRefType()));
//            }
//
//            JavaMethodCallElement item = new JavaMethodCallElement((PsiMethod)completion).setQualifierSubstitutor(substitutor);
//            item.setForcedQualifier(completionElement.getQualifierText());
//            return Collections.singletonList(item);
//        }
//        if (completion instanceof PsiVariable) {
//            return Collections.singletonList(new VariableLookupItem((PsiVariable)completion).setSubstitutor(substitutor).qualifyIfNeeded(reference));
//        }
//        if (completion instanceof PsiPackage) {
//            return Collections.singletonList(new PackageLookupItem((PsiPackage)completion, reference.getElement()));
//        }
        return Collections.singletonList(LookupElementBuilder.create(completion));
//        return Collections.singletonList(LookupItemUtil.objectToLookupItem(completion));
    }

    @NotNull
    static PsiReferenceExpression createReference(@NotNull String text, @NotNull PsiElement context) {
        return (PsiReferenceExpression) JavaPsiFacade.getElementFactory(context.getProject()).createExpressionFromText(text, context);
    }

    public static FakePsiElement createContextWithXxxVariable(@NotNull PsiElement place, @NotNull PsiType varType) {
        return new FakePsiElement() {
            @Override
            public boolean processDeclarations(@NotNull PsiScopeProcessor processor,
                                               @NotNull ResolveState state,
                                               PsiElement lastParent,
                                               @NotNull PsiElement place) {
                return processor.execute(new LightVariableBuilder<>("xxx", varType, place), ResolveState.initial());
            }

            @Override
            public PsiElement getParent() {
                return place;
            }
        };
    }


    @NotNull
    private static List<PsiType> getQualifierCastTypes(PsiJavaReference javaReference, CompletionParameters parameters) {
        if (javaReference instanceof PsiReferenceExpression) {
            final PsiReferenceExpression refExpr = (PsiReferenceExpression)javaReference;
            final PsiExpression qualifier = refExpr.getQualifierExpression();
            if (qualifier != null) {
                final Project project = qualifier.getProject();
                PairFunction<PsiExpression, CompletionParameters, PsiType> evaluator = refExpr.getContainingFile().getCopyableUserData(DYNAMIC_TYPE_EVALUATOR);
                if (evaluator != null) {
                    PsiType type = evaluator.fun(qualifier, parameters);
                    if (type != null) {
                        return Collections.singletonList(type);
                    }
                }

                return GuessManager.getInstance(project).getControlFlowExpressionTypeConjuncts(qualifier, parameters.getInvocationCount() > 1);
            }
        }
        return Collections.emptyList();
    }

    @Nullable
    static PsiElement resolveReference(final PsiReference psiReference) {
        if (psiReference instanceof PsiPolyVariantReference) {
            final ResolveResult[] results = ((PsiPolyVariantReference)psiReference).multiResolve(true);
            if (results.length == 1) return results[0].getElement();
        }
        return psiReference.resolve();
    }

    public static int findQualifiedNameStart(@NotNull InsertionContext context) {
        int start = context.getTailOffset() - 1;
        while (start >= 0) {
            char ch = context.getDocument().getCharsSequence().charAt(start);
            if (!Character.isJavaIdentifierPart(ch) && ch != '.') break;
            start--;
        }
        return start + 1;
    }


    public static boolean isSourceLevelAccessible(@NotNull PsiElement context,
                                                  @NotNull PsiClass psiClass,
                                                  final boolean pkgContext) {
        return isSourceLevelAccessible(context, psiClass, pkgContext, psiClass.getContainingClass());
    }

    private static boolean isSourceLevelAccessible(PsiElement context,
                                                   @NotNull PsiClass psiClass,
                                                   final boolean pkgContext,
                                                   @Nullable PsiClass qualifierClass) {
        if (!JavaPsiFacade.getInstance(psiClass.getProject()).getResolveHelper().isAccessible(psiClass, context, qualifierClass)) {
            return false;
        }

        if (pkgContext) {
            PsiClass topLevel = PsiUtil.getTopLevelClass(psiClass);
            if (topLevel != null) {
                String fqName = topLevel.getQualifiedName();
                if (fqName != null && StringUtil.isEmpty(StringUtil.getPackageName(fqName))) {
                    return false;
                }
            }
        }

        return true;
    }
}
