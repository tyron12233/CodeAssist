package com.tyron.completion.psi.completion;

import static com.tyron.completion.java.patterns.PsiElementPatterns.insideStarting;
import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.psiElement;
import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.string;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.not;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.or;
import static org.jetbrains.kotlin.com.intellij.psi.SyntaxTraverser.psiApi;

import com.tyron.completion.java.patterns.PsiAnnotationPattern;
import com.tyron.completion.java.patterns.PsiElementPatterns;
import com.tyron.completion.lookup.LookupElementBuilder;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.psi.completion.item.KeywordLookupItem;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.ObjectPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.psi.JavaCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotationParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiCatchSection;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassObjectAccessExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiConditionalExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiDeclarationStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiForStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiIfStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaModule;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiLabeledStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable;
import org.jetbrains.kotlin.com.intellij.psi.PsiLoopStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiRecordHeader;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiResourceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiResourceList;
import org.jetbrains.kotlin.com.intellij.psi.PsiStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchLabelStatementBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiTryStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiUnaryExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.kotlin.com.intellij.psi.templateLanguages.OuterLanguageElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class JavaKeywordCompletion {

    public static final ElementPattern<PsiElement> AFTER_DOT =
            psiElement().afterLeaf(psiElement().withText(StandardPatterns.string().oneOf(".")));

    static final ElementPattern<PsiElement> VARIABLE_AFTER_FINAL =
            psiElement().afterLeaf(psiElement().withText(StandardPatterns.string()
                    .oneOf(PsiKeyword.FINAL))).inside(psiElement(PsiDeclarationStatement.class));

    private static boolean isStatementCodeFragment(PsiFile file) {
        return file instanceof JavaCodeFragment &&
               !(file instanceof PsiExpressionCodeFragment ||
                 file instanceof PsiJavaCodeReferenceCodeFragment ||
                 file instanceof PsiTypeCodeFragment);
    }

    static boolean isEndOfBlock(@NotNull PsiElement element) {
        PsiElement prev = prevSignificantLeaf(element);
        if (prev == null) {
            PsiFile file = element.getContainingFile();
            return !(file instanceof PsiCodeFragment) || isStatementCodeFragment(file);
        }

        if (psiElement().inside(PsiAnnotationPattern.PSI_ANNOTATION_PATTERN).accepts(prev)) {
            return false;
        }

        if (prev instanceof OuterLanguageElement) {
            return true;
        }
        if (psiElement().withText(string().oneOf("{", "}", ";", ":", "else")).accepts(prev)) {
            return true;
        }
        if (prev.textMatches(")")) {
            PsiElement parent = prev.getParent();
            if (parent instanceof PsiParameterList) {
                return PsiTreeUtil.getParentOfType(PsiTreeUtil.prevVisibleLeaf(element),
                        PsiDocComment.class) != null;
            }

            return !(parent instanceof PsiExpressionList ||
                     parent instanceof PsiTypeCastExpression ||
                     parent instanceof PsiRecordHeader);
        }

        return false;
    }

    static final ElementPattern<PsiElement> START_SWITCH =
            StandardPatterns.or(psiElement().afterLeaf(psiElement().withText("{")
                            .withParent(PsiCodeBlock.class)),
                    psiElement().afterLeaf(psiElement().withText("{")
                            .withParent(PsiSwitchBlock.class)));

    private static final ElementPattern<PsiElement> SUPER_OR_THIS_PATTERN = and(
            JavaSmartCompletionContributor.INSIDE_EXPRESSION,
            not(psiElement().afterLeaf(psiElement().withText(PsiKeyword.CASE))),
            not(psiElement().afterLeaf(psiElement().withText(".")
                    .afterLeaf(psiElement().withText(StandardPatterns.string()
                            .oneOf(PsiKeyword.THIS, PsiKeyword.SUPER))))),
            not(psiElement().inside(psiElement(PsiAnnotation.class))),
            not(START_SWITCH),
            not(JavaMemberNameCompletionContributor.INSIDE_TYPE_PARAMS_PATTERN));

    private static final ElementPattern<PsiElement> INSIDE_PARAMETER_LIST =
            psiElement().withParent(psiElement(PsiJavaCodeReferenceElement.class).inside(psiElement().withParent(
                    psiElement(PsiParameterList.class).andNot(psiElement(PsiAnnotationParameterList.class)))));

    @SafeVarargs
    public static <E> ElementPattern<E> and(final ElementPattern<? extends E>... patterns) {
        final List<InitialPatternCondition> initial = new SmartList<>();
        for (ElementPattern<?> pattern : patterns) {
            initial.add(pattern.getCondition().getInitialCondition());
        }

        ObjectPattern.Capture<E> result = composeInitialConditions(initial);
        for (ElementPattern pattern : patterns) {
            for (PatternCondition<?> condition : (List<PatternCondition<?>>) pattern.getCondition()
                    .getConditions()) {
                result = result.with((PatternCondition<? super E>) condition);
            }
        }
        return result;
    }

    @NotNull
    private static <E> ObjectPattern.Capture<E> composeInitialConditions(final List<?
            extends InitialPatternCondition> initial) {
        return new ObjectPattern.Capture<>(new InitialPatternCondition(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                for (final InitialPatternCondition pattern : initial) {
                    if (!pattern.accepts(o, context)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                boolean first = true;
                for (final InitialPatternCondition pattern : initial) {
                    if (!first) {
                        builder.append("\n").append(indent);
                    }
                    first = false;
                    pattern.append(builder, indent + "  ");
                }
            }
        });
    }

    static final Set<String> PRIMITIVE_TYPES = ContainerUtil.newLinkedHashSet(Arrays.asList(
            PsiKeyword.SHORT,
            PsiKeyword.BOOLEAN,
            PsiKeyword.DOUBLE,
            PsiKeyword.LONG,
            PsiKeyword.INT,
            PsiKeyword.FLOAT,
            PsiKeyword.CHAR,
            PsiKeyword.BYTE));

    private final PsiElement myPosition;
    private final CompletionList.Builder myBuilder;
    private final PsiElement myPrevLeaf;

    public JavaKeywordCompletion(PsiElement position, CompletionList.Builder builder) {
        myPosition = position;
        myBuilder = builder;
        myPrevLeaf = prevSignificantLeaf(myPosition);

        addKeywords();
    }

    private static PsiElement prevSignificantLeaf(PsiElement position) {
        return position;
    }

    public void addKeywords() {
        if (PsiTreeUtil.getNonStrictParentOfType(myPosition,
                PsiLiteralExpression.class,
                PsiComment.class) != null) {
            return;
        }

        if (psiElement().afterLeaf(psiElement().withText("::")).accepts(myPosition)) {
            return;
        }

        PsiFile file = myPosition.getContainingFile();
        if (PsiJavaModule.MODULE_INFO_FILE.equals(file.getName()) &&
            PsiUtil.isLanguageLevel9OrHigher(file)) {
//            addModuleKeywords();
            return;
        }

        addFinal();

        boolean statementPosition = isStatementPosition(myPosition);
        if (statementPosition) {
            addCaseDefault();

            if (START_SWITCH.accepts(myPosition)) {
                return;
            }

            addBreakContinue();
//            addStatementKeywords();

            if (myPrevLeaf.textMatches("}") &&
                myPrevLeaf.getParent() instanceof PsiCodeBlock &&
                myPrevLeaf.getParent().getParent() instanceof PsiTryStatement &&
                myPrevLeaf.getParent().getNextSibling() instanceof PsiErrorElement) {
                return;
            }
        }

        addExpressionKeywords(statementPosition);

        addThisSuper();

        addInstanceOf();

        addClassKeywords();
    }

    private void addBreakContinue() {
        PsiLoopStatement loop = PsiTreeUtil.getParentOfType(myPosition, PsiLoopStatement.class, true, PsiLambdaExpression.class, PsiMember.class);

        KeywordLookupItem br = createKeyword(PsiKeyword.BREAK);
        KeywordLookupItem cont = createKeyword(PsiKeyword.CONTINUE);

        if (loop != null && PsiTreeUtil.isAncestor(loop.getBody(), myPosition, false)) {
            myBuilder.addItem(br);
            myBuilder.addItem(cont);
        }

        for (PsiLabeledStatement labeled : psiApi().parents(myPosition).takeWhile(psiElement -> !(psiElement instanceof PsiMember)).filter(PsiLabeledStatement.class)) {
            myBuilder.addItem(LookupElementBuilder.create("break " + labeled).bold());
        }
    }

    private void addCaseDefault() {
        PsiSwitchBlock switchBlock = getSwitchFromLabelPosition(myPosition);
        if (switchBlock == null) {
            return;
        }

        myBuilder.addItem(createKeyword(PsiKeyword.CASE));
        myBuilder.addItem(createKeyword(PsiKeyword.DEFAULT));
    }

    private static PsiSwitchBlock getSwitchFromLabelPosition(PsiElement position) {
        PsiStatement statement = PsiTreeUtil.getParentOfType(position, PsiStatement.class, false, PsiMember.class);
        if (statement == null || statement.getTextRange().getStartOffset() != position.getTextRange().getStartOffset()) {
            return null;
        }

        if (!(statement instanceof PsiSwitchLabelStatementBase) && statement.getParent() instanceof PsiCodeBlock) {
            return ObjectUtils.tryCast(statement.getParent().getParent(), PsiSwitchBlock.class);
        }
        return null;
    }

    private void addFinal() {
        PsiStatement statement = PsiTreeUtil.getParentOfType(myPosition,
                PsiExpressionStatement.class,
                PsiDeclarationStatement.class);
        if (statement != null &&
            statement.getTextRange().getStartOffset() ==
            myPosition.getTextRange().getStartOffset()) {
            if (!psiElement().withSuperParent(2, psiElement(PsiSwitchBlock.class))
                    .afterLeaf(psiElement().withText("{"))
                    .accepts(statement)) {
                PsiTryStatement tryStatement =
                        PsiTreeUtil.getParentOfType(myPrevLeaf, PsiTryStatement.class);
                if (tryStatement == null ||
                    tryStatement.getCatchSections().length > 0 ||
                    tryStatement.getFinallyBlock() != null ||
                    tryStatement.getResourceList() != null) {

                    KeywordLookupItem item = createKeyword(PsiKeyword.FINAL);
//                    if (statement.getParent() instanceof PsiSwitchLabeledRuleStatement) {
//                        item = wrapRuleIntoBlock(item);
//                    }
                    myBuilder.addItem(item);
                    return;
                }
            }
        }

        if ((isInsideParameterList(myPosition) || isAtCatchOrResourceVariableStart(myPosition)) &&
            !psiElement().afterLeaf(psiElement().withText(PsiKeyword.FINAL)).accepts(myPosition) &&
            !AFTER_DOT.accepts(myPosition)) {
            myBuilder.addItem(createKeyword(PsiKeyword.FINAL));
        }
    }

    public KeywordLookupItem createKeyword(String keyword) {
        return new KeywordLookupItem(JavaPsiFacade.getInstance(myPosition.getProject())
                .getElementFactory()
                .createKeyword(keyword), myPosition);
    }

    public static boolean isInsideParameterList(PsiElement position) {
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(position);
        PsiModifierList modifierList = PsiTreeUtil.getParentOfType(prev, PsiModifierList.class);
        if (modifierList != null) {
            if (PsiTreeUtil.isAncestor(modifierList, position, false)) {
                return false;
            }
            PsiElement parent = modifierList.getParent();
            return parent instanceof PsiParameterList ||
                   parent instanceof PsiParameter && parent.getParent() instanceof PsiParameterList;
        }
        return INSIDE_PARAMETER_LIST.accepts(position);
    }

    private static boolean isAtCatchOrResourceVariableStart(PsiElement position) {
        PsiElement type = PsiTreeUtil.getParentOfType(position, PsiTypeElement.class);
        if (type != null &&
            type.getTextRange().getStartOffset() == position.getTextRange().getStartOffset()) {
            PsiElement parent = type.getParent();
            if (parent instanceof PsiVariable) {
                parent = parent.getParent();
            }
            return parent instanceof PsiCatchSection || parent instanceof PsiResourceList;
        }
        return psiElement().inside(psiElement(PsiResourceExpression.class)).accepts(position);
    }

    private void addClassKeywords() {
        if (isSuitableForClass(myPosition)) {
            for (String modifier : PsiModifier.MODIFIERS) {
                myBuilder.addItem(createKeyword(modifier));
            }

            if (insideStarting(or(psiElement(PsiLocalVariable.class),
                    psiElement(PsiExpression.class))).accepts(myPosition)) {
                myBuilder.addItem(createKeyword(PsiKeyword.CLASS));
//                myBuilder.addItem(createKeyword);
            }

            if (PsiTreeUtil.getParentOfType(myPosition,
                    PsiExpression.class,
                    true,
                    PsiMember.class) == null &&
                PsiTreeUtil.getParentOfType(myPosition,
                        PsiCodeBlock.class,
                        true,
                        PsiMember.class) == null) {
                List<String> keywords = new ArrayList<>();
                keywords.add(PsiKeyword.CLASS);
                keywords.add(PsiKeyword.INTERFACE);

                if (PsiUtil.isLanguageLevel5OrHigher(myPosition)) {
                    keywords.add(PsiKeyword.ENUM);
                }

                // TODO: recommend class declaration
                for (String keyword : keywords) {
                    myBuilder.addItem(createKeyword(keyword));
                }
            }
        }
    }

    private void addPrimitiveTypes() {

    }

    private void addThisSuper() {
        if (SUPER_OR_THIS_PATTERN.accepts(myPosition)) {
            final boolean afterDot = AFTER_DOT.accepts(myPosition);
            final boolean insideQualifierClass = isInsideQualifierClass();
            final boolean insideInheritorClass = isInsideInheritorClass();
            if (!afterDot || insideQualifierClass || insideInheritorClass) {
                if (!afterDot || insideQualifierClass) {
                    myBuilder.addItem(createKeyword(PsiKeyword.THIS));
                }

                final KeywordLookupItem superItem = createKeyword(PsiKeyword.SUPER);
                if (psiElement().afterLeaf(psiElement().withText("}")
                        .withSuperParent(2,
                                psiElement(PsiMethod.class).with(new PatternCondition<PsiMethod>(
                                        "constructor") {
                                    @Override
                                    public boolean accepts(@NotNull PsiMethod psiMethod,
                                                           ProcessingContext processingContext) {
                                        return psiMethod.isConstructor();
                                    }
                                }))).accepts(myPosition)) {
                    final PsiMethod method = PsiTreeUtil.getParentOfType(myPosition,
                            PsiMethod.class,
                            false,
                            PsiClass.class);
                    assert method != null;
                    final boolean hasParams = superConstructorHasParameters(method);
                    myBuilder.addItem(superItem);
                    return;
                }
                myBuilder.addItem(superItem);
            }
        }
    }

    private boolean isInsideQualifierClass() {
        if (myPosition.getParent() instanceof PsiJavaCodeReferenceElement) {
            final PsiElement qualifier =
                    ((PsiJavaCodeReferenceElement) myPosition.getParent()).getQualifier();
            if (qualifier instanceof PsiJavaCodeReferenceElement) {
                final PsiElement qualifierClass =
                        ((PsiJavaCodeReferenceElement) qualifier).resolve();
                if (qualifierClass instanceof PsiClass) {
                    PsiElement parent = myPosition;
                    final PsiManager psiManager = myPosition.getManager();
                    while ((parent = PsiTreeUtil.getParentOfType(parent, PsiClass.class, true)) !=
                           null) {
                        if (psiManager.areElementsEquivalent(parent, qualifierClass)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isInsideInheritorClass() {
        if (myPosition.getParent() instanceof PsiJavaCodeReferenceElement) {
            final PsiElement qualifier =
                    ((PsiJavaCodeReferenceElement) myPosition.getParent()).getQualifier();
            if (qualifier instanceof PsiJavaCodeReferenceElement) {
                final PsiElement qualifierClass =
                        ((PsiJavaCodeReferenceElement) qualifier).resolve();
                if (qualifierClass instanceof PsiClass &&
                    ((PsiClass) qualifierClass).isInterface()) {
                    PsiElement parent = myPosition;
                    while ((parent = PsiTreeUtil.getParentOfType(parent, PsiClass.class, true)) !=
                           null) {
                        if (PsiUtil.getEnclosingStaticElement(myPosition, (PsiClass) parent) ==
                            null &&
                            ((PsiClass) parent).isInheritor((PsiClass) qualifierClass, true)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean superConstructorHasParameters(PsiMethod method) {
        final PsiClass psiClass = method.getContainingClass();
        if (psiClass == null) {
            return false;
        }

        final PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null) {
            for (final PsiMethod psiMethod : superClass.getConstructors()) {
                final PsiResolveHelper resolveHelper =
                        JavaPsiFacade.getInstance(method.getProject()).getResolveHelper();
                if (resolveHelper.isAccessible(psiMethod, method, null) &&
                    !psiMethod.getParameterList().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addExpressionKeywords(boolean statementPosition) {
        if (isExpressionPosition(myPosition)) {
            PsiElement parent = myPosition.getParent();
            PsiElement grandParent = parent == null ? null : parent.getParent();
            boolean allowExprKeywords = !(grandParent instanceof PsiExpressionStatement) &&
                                        !(grandParent instanceof PsiUnaryExpression);
            if (PsiTreeUtil.getParentOfType(myPosition, PsiAnnotation.class) == null) {
                if (!statementPosition) {
                    myBuilder.addItem(createKeyword(PsiKeyword.NEW));
                }
                if (allowExprKeywords) {
                    myBuilder.addItem(createKeyword(PsiKeyword.NULL));
                }
            }

            if (allowExprKeywords && mayExpectBoolean()) {
                myBuilder.addItem(createKeyword(PsiKeyword.TRUE));
                myBuilder.addItem(createKeyword(PsiKeyword.FALSE));
            }
        }

        if (isQualifiedNewContext()) {
            myBuilder.addItem(createKeyword(PsiKeyword.NEW));
        }
    }

    private boolean isQualifiedNewContext() {
        if (myPosition.getParent() instanceof PsiReferenceExpression) {
            PsiExpression qualifier =
                    ((PsiReferenceExpression) myPosition.getParent()).getQualifierExpression();
            PsiClass qualifierClass = PsiUtil.resolveClassInClassTypeOnly(qualifier ==
                                                                          null ? null :
                    qualifier.getType());
            return qualifierClass != null &&
                   ContainerUtil.exists(qualifierClass.getAllInnerClasses(),
                           inner -> canBeCreatedInQualifiedNew(qualifierClass, inner));
        }
        return false;
    }

    private boolean canBeCreatedInQualifiedNew(PsiClass outer, PsiClass inner) {
        PsiMethod[] constructors = inner.getConstructors();
        return !inner.hasModifierProperty(PsiModifier.STATIC) &&
               PsiUtil.isAccessible(inner, myPosition, outer) &&
               (constructors.length == 0 ||
                ContainerUtil.exists(constructors,
                        c -> PsiUtil.isAccessible(c, myPosition, outer)));
    }

    private static boolean mayExpectBoolean() {
        return false;
    }

    private static boolean isExpressionPosition(PsiElement position) {
        if (insideStarting(psiElement(PsiClassObjectAccessExpression.class)).accepts(position)) {
            return true;
        }

        PsiElement parent = position.getParent();
        if (!(parent instanceof PsiReferenceExpression) ||
            ((PsiReferenceExpression) parent).isQualified()
//            JavaCompletionContributor.IN_SWITCH_LABEL.accepts(position)) {
        ) {
            return false;
        }
        if (parent.getParent() instanceof PsiExpressionStatement) {
            PsiElement previous = PsiTreeUtil.skipWhitespacesBackward(parent.getParent());
            return previous == null || !(previous.getLastChild() instanceof PsiErrorElement);
        }
        return true;
    }

    public static boolean isInstanceofPlace(PsiElement position) {
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev == null) {
            return false;
        }

        PsiElement expr = PsiTreeUtil.getParentOfType(prev, PsiExpression.class);
        if (expr != null &&
            expr.getTextRange().getEndOffset() == prev.getTextRange().getEndOffset() &&
            PsiTreeUtil.getParentOfType(expr, PsiAnnotation.class) == null) {
            return true;
        }

        if (position instanceof PsiIdentifier && position.getParent() instanceof PsiLocalVariable) {
            PsiType type = ((PsiLocalVariable) position.getParent()).getType();
            if (type instanceof PsiClassType && ((PsiClassType) type).resolve() == null) {
                PsiElement grandParent = position.getParent().getParent();
                return !(grandParent instanceof PsiDeclarationStatement) ||
                       !(grandParent.getParent() instanceof PsiForStatement) ||
                       ((PsiForStatement) grandParent.getParent()).getInitialization() !=
                       grandParent;
            }
        }

        return false;
    }

    private void addInstanceOf() {
        if (isInstanceofPlace(myPosition)) {
            myBuilder.addItem(createKeyword(PsiKeyword.INSTANCEOF));
        }
    }

    public static boolean isSuitableForClass(PsiElement position) {
        if (psiElement().afterLeaf(psiElement().withText("@")).accepts(position) ||
            PsiTreeUtil.getNonStrictParentOfType(position,
                    PsiLiteralExpression.class,
                    PsiComment.class,
                    PsiExpressionCodeFragment.class) != null) {
            return false;
        }

        PsiElement prev = prevSignificantLeaf(position);
        if (prev == null) {
            return true;
        }

        if (psiElement().without(new PatternCondition<PsiElement>("withoutText") {
                    @Override
                    public boolean accepts(@NotNull PsiElement psiElement,
                                           ProcessingContext processingContext) {
                        return psiElement.getText().equals(".");
                    }
                })
                    .inside(psiElement(PsiModifierList.class).withParent(not(psiElement(PsiParameter.class)).andNot(
                            psiElement(PsiParameterList.class))))
                    .accepts(prev) &&
            (!psiElement().inside(psiElement(PsiAnnotationParameterList.class)).accepts(prev) ||
             prev.textMatches(")"))) {
            return true;
        }

        if (PsiElementPatterns.withParents(PsiErrorElement.class, PsiFile.class)
                .accepts(position)) {
            return true;
        }

        return isEndOfBlock(position);
    }

    private static boolean isForLoopMachinery(PsiElement position) {
        PsiStatement statement = PsiTreeUtil.getParentOfType(position, PsiStatement.class);
        if (statement == null) {
            return false;
        }

        return statement instanceof PsiForStatement ||
               statement.getParent() instanceof PsiForStatement &&
               statement != ((PsiForStatement) statement.getParent()).getBody();
    }

    private static boolean isStatementPosition(PsiElement position) {
        if (psiElement().withSuperParent(2, psiElement(PsiConditionalExpression.class))
                .andNot(insideStarting(psiElement(PsiConditionalExpression.class)))
                .accepts(position)) {
            return false;
        }

        if (isEndOfBlock(position) &&
            PsiTreeUtil.getParentOfType(position, PsiCodeBlock.class, true, PsiMember.class) !=
            null) {
            return !isForLoopMachinery(position);
        }

        if (PsiElementPatterns.withParents(PsiReferenceExpression.class,
                        PsiExpressionStatement.class,
                        PsiIfStatement.class)
                .andNot(psiElement().afterLeaf(psiElement().withText(".")))
                .accepts(position)) {
            PsiElement stmt = position.getParent().getParent();
            PsiIfStatement ifStatement = (PsiIfStatement) stmt.getParent();
            return ifStatement.getElseBranch() == stmt || ifStatement.getThenBranch() == stmt;
        }

        return false;
    }
}