package com.tyron.psi.completions.lang.java.guess;

import com.tyron.psi.completions.lang.java.datafFlow.ControlFlowAnalyzer;
import com.tyron.psi.completions.lang.java.datafFlow.DfaPsiUtil;
import com.tyron.psi.completions.lang.java.search.searches.ReferencesSearch;
import com.tyron.psi.completions.lang.java.util.CallMatcher;
import com.tyron.psi.completions.lang.java.util.psi.ExpressionUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.search.LocalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.BitUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;
import org.jetbrains.kotlin.fir.lightTree.fir.TypeConstraint;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

public class GuessManagerImpl extends GuessManager {
    private final MethodPatternMap myMethodPatternMap = new MethodPatternMap();

    {
        initMethodPatterns();
    }

    private void initMethodPatterns() {
        // Collection
        myMethodPatternMap.addPattern(new MethodPattern("add", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("contains", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("remove", 1, 0));

        // Vector
        myMethodPatternMap.addPattern(new MethodPattern("add", 2, 1));
        myMethodPatternMap.addPattern(new MethodPattern("addElement", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("elementAt", 1, -1));
        myMethodPatternMap.addPattern(new MethodPattern("firstElement", 0, -1));
        myMethodPatternMap.addPattern(new MethodPattern("lastElement", 0, -1));
        myMethodPatternMap.addPattern(new MethodPattern("get", 1, -1));
        myMethodPatternMap.addPattern(new MethodPattern("indexOf", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("indexOf", 2, 0));
        myMethodPatternMap.addPattern(new MethodPattern("lastIndexOf", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("lastIndexOf", 2, 0));
        myMethodPatternMap.addPattern(new MethodPattern("insertElementAt", 2, 0));
        myMethodPatternMap.addPattern(new MethodPattern("removeElement", 1, 0));
        myMethodPatternMap.addPattern(new MethodPattern("set", 2, 1));
        myMethodPatternMap.addPattern(new MethodPattern("setElementAt", 2, 0));
    }

    private final Project myProject;

    public GuessManagerImpl(Project project) {
        myProject = project;
    }

    @Override
    public PsiType[] guessContainerElementType(PsiExpression containerExpr, TextRange rangeToIgnore) {
        HashSet<PsiType> typesSet = new HashSet<>();

        PsiType type = containerExpr.getType();
        PsiType elemType;
        if ((elemType = getGenericElementType(type)) != null) return new PsiType[]{elemType};

        if (containerExpr instanceof PsiReferenceExpression){
            PsiElement refElement = ((PsiReferenceExpression)containerExpr).resolve();
            if (refElement instanceof PsiVariable){

                PsiFile file = refElement.getContainingFile();
                if (file == null){
                    file = containerExpr.getContainingFile(); // implicit variable in jsp
                }
                HashSet<PsiVariable> checkedVariables = new HashSet<>();
                addTypesByVariable(typesSet, (PsiVariable)refElement, file, checkedVariables, CHECK_USAGE | CHECK_DOWN, rangeToIgnore);
                checkedVariables.clear();
                addTypesByVariable(typesSet, (PsiVariable)refElement, file, checkedVariables, CHECK_UP, rangeToIgnore);
            }
        }

        return typesSet.toArray(PsiType.createArray(typesSet.size()));
    }

    @Override
    public PsiType[] guessTypeToCast(PsiExpression expr) {
        return new PsiType[0];
    }

    @Override
    public MultiMap<PsiExpression, PsiType> getControlFlowExpressionTypes(PsiExpression forPlace, boolean honorAssignments) {
        PsiElement scope = DfaPsiUtil.getTopmostBlockInSameClass(forPlace);
        if (scope == null) {
            PsiFile file = forPlace.getContainingFile();
            if (!(file instanceof PsiCodeFragment)) {
                return MultiMap.create();
            }
            scope = file;
        }
        return MultiMap.create();
    }

    @Override
    public List<PsiType> getControlFlowExpressionTypeConjuncts(PsiExpression expr, boolean honorAssignments) {
        if (expr.getType() instanceof PsiPrimitiveType) {
            return Collections.emptyList();
        }
        PsiExpression place = PsiUtil.skipParenthesizedExprDown(expr);
        if (place == null) return Collections.emptyList();

        List<PsiType> result = null;
        if (!ControlFlowAnalyzer.inlinerMayInferPreciseType(place)) {
            GuessTypeVisitor visitor = tryGuessingTypeWithoutDfa(place, honorAssignments);
            if (!visitor.isDfaNeeded()) {
                result = visitor.mySpecificType == null ?
                        Collections.emptyList() : Collections.singletonList(DfaPsiUtil.tryGenerify(expr, visitor.mySpecificType));
            }
        }
        if (result == null) {
            PsiType psiType = getTypeFromDataflow(expr, honorAssignments);
            if (psiType instanceof PsiIntersectionType) {
                result = ContainerUtil.mapNotNull(((PsiIntersectionType)psiType).getConjuncts(), type -> DfaPsiUtil.tryGenerify(expr, type));
            }
            else if (psiType != null) {
                result = Collections.singletonList(DfaPsiUtil.tryGenerify(expr, psiType));
            }
            else {
                result = Collections.emptyList();
            }
        }
        result = ContainerUtil.filter(result, t -> {
            PsiClass typeClass = PsiUtil.resolveClassInType(t);
            return typeClass == null || PsiUtil.isAccessible(typeClass, expr, null);
        });
        if (result.equals(Collections.singletonList(TypeConversionUtil.erasure(expr.getType())))) {
            return Collections.emptyList();
        }
        return result;
    }

    @Nullable
    private static PsiType getTypeFromDataflow(PsiExpression forPlace, boolean honorAssignments) {
        PsiType type = forPlace.getType();
//        TypeConstraint initial = type == null ? TypeConstraints.TOP : TypeConstraints.instanceOf(type);
//        PsiElement scope = DfaPsiUtil.getTopmostBlockInSameClass(forPlace);
//        if (scope == null) {
//            PsiFile file = forPlace.getContainingFile();
//            if (!(file instanceof PsiCodeFragment)) {
//                return null;
//            }
//            scope = file;
//        }
        return type;
    }

    @NotNull
    private static GuessTypeVisitor tryGuessingTypeWithoutDfa(PsiExpression place, boolean honorAssignments) {
        List<PsiElement> exprsAndVars = getPotentiallyAffectingElements(place);
        GuessTypeVisitor visitor = new GuessTypeVisitor(place, honorAssignments);
        for (PsiElement e : exprsAndVars) {
            e.accept(visitor);
            if (e == place || visitor.isDfaNeeded()) {
                break;
            }
        }
        return visitor;
    }

    private static List<PsiElement> getPotentiallyAffectingElements(PsiExpression place) {
        PsiElement topmostBlock = getTopmostBlock(place);
        return CachedValuesManager.getCachedValue(topmostBlock, () -> {
            List<PsiElement> list = SyntaxTraverser.psiTraverser(topmostBlock).filter(e -> e instanceof PsiExpression || e instanceof PsiLocalVariable).toList();
            return new CachedValueProvider.Result<>(list, topmostBlock);
        });
    }

    private static PsiElement getTopmostBlock(PsiElement scope) {
        assert scope.isValid();
        PsiElement lastScope = scope;
        while (true) {
            final PsiCodeBlock lastCodeBlock = PsiTreeUtil.getParentOfType(lastScope, PsiCodeBlock.class, true);
            if (lastCodeBlock == null) {
                break;
            }
            lastScope = lastCodeBlock;
        }
        if (lastScope == scope) {
            PsiFile file = scope.getContainingFile();
            if (file instanceof PsiCodeFragment) {
                return file;
            }
        }
        return lastScope;
    }


    private static class GuessTypeVisitor extends JavaElementVisitor {
        private static final CallMatcher OBJECT_GET_CLASS =
                CallMatcher.exactInstanceCall(CommonClassNames.JAVA_LANG_OBJECT, "getClass").parameterCount(0);
        private final @NotNull PsiExpression myPlace;
        PsiType mySpecificType;
        private boolean myNeedDfa;
        private boolean myDeclared;
        private final boolean myHonorAssignments;

        GuessTypeVisitor(@NotNull PsiExpression place, boolean honorAssignments) {
            myPlace = place;
            myHonorAssignments = honorAssignments;
        }

        protected void handleAssignment(@Nullable PsiExpression expression) {
            if (!myHonorAssignments || expression == null) return;
            PsiType type = expression.getType();
            if (type instanceof PsiPrimitiveType) {
                type = ((PsiPrimitiveType)type).getBoxedType(expression);
            }
            PsiType rawType = type instanceof PsiClassType ? ((PsiClassType)type).rawType() : type;
            if (rawType == null || rawType.equals(PsiType.NULL)) return;
            if (mySpecificType == null) {
                mySpecificType = rawType;
            }
            else if (!mySpecificType.equals(rawType)) {
                myNeedDfa = true;
            }
        }

        @Override
        public void visitAssignmentExpression(PsiAssignmentExpression expression) {
            if (ExpressionVariableDescriptor.EXPRESSION_HASHING_STRATEGY.equals(expression.getLExpression(), myPlace)) {
                handleAssignment(expression.getRExpression());
            }
            super.visitAssignmentExpression(expression);
        }

        @Override
        public void visitLocalVariable(PsiLocalVariable variable) {
            if (ExpressionUtils.isReferenceTo(myPlace, variable)) {
                myDeclared = true;
                handleAssignment(variable.getInitializer());
            }
            super.visitLocalVariable(variable);
        }

        @Override
        public void visitTypeCastExpression(PsiTypeCastExpression expression) {
            PsiExpression operand = expression.getOperand();
            if (operand != null && ExpressionVariableDescriptor.EXPRESSION_HASHING_STRATEGY.equals(operand, myPlace)) {
                myNeedDfa = true;
            }
            super.visitTypeCastExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(PsiMethodCallExpression call) {
            if (OBJECT_GET_CLASS.test(call)) {
                PsiExpression qualifier = ExpressionUtils.getEffectiveQualifier(call.getMethodExpression());
                if (qualifier != null && ExpressionVariableDescriptor.EXPRESSION_HASHING_STRATEGY.equals(qualifier, myPlace)) {
                    myNeedDfa = true;
                }
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitInstanceOfExpression(PsiInstanceOfExpression expression) {
            if (ExpressionVariableDescriptor.EXPRESSION_HASHING_STRATEGY.equals(expression.getOperand(), myPlace)) {
                myNeedDfa = true;
            }
            super.visitInstanceOfExpression(expression);
        }

        public boolean isDfaNeeded() {
            if (myNeedDfa) return true;
            if (myDeclared || mySpecificType == null) return false;
            PsiType type = myPlace.getType();
            PsiType rawType = type instanceof PsiClassType ? ((PsiClassType)type).rawType() : type;
            return !mySpecificType.equals(rawType);
        }
    }

    private static final int CHECK_USAGE = 0x01;
    private static final int CHECK_UP = 0x02;
    private static final int CHECK_DOWN = 0x04;

    private void addTypesByVariable(HashSet<? super PsiType> typesSet,
                                    PsiVariable var,
                                    PsiFile scopeFile,
                                    HashSet<? super PsiVariable> checkedVariables,
                                    int flags,
                                    TextRange rangeToIgnore) {
        if (!checkedVariables.add(var)) return;
        //System.out.println("analyzing usages of " + var + " in file " + scopeFile);
        SearchScope searchScope = new LocalSearchScope(scopeFile);

        if (BitUtil.isSet(flags, CHECK_USAGE) || BitUtil.isSet(flags, CHECK_DOWN)) {
            for (PsiReference varRef : ReferencesSearch.search(var, searchScope, false)) {
                PsiElement ref = varRef.getElement();

                if (BitUtil.isSet(flags, CHECK_USAGE)) {
                    PsiType type = guessElementTypeFromReference(myMethodPatternMap, ref, rangeToIgnore);
                    if (type != null && !(type instanceof PsiPrimitiveType)) {
                        typesSet.add(type);
                    }
                }

                if (BitUtil.isSet(flags, CHECK_DOWN)) {
                    if (ref.getParent() instanceof PsiExpressionList && ref.getParent().getParent() instanceof PsiMethodCallExpression) { //TODO : new
                        PsiExpressionList list = (PsiExpressionList) ref.getParent();
                        int argIndex = ArrayUtil.indexOf(list.getExpressions(), ref);

                        PsiMethodCallExpression methodCall = (PsiMethodCallExpression) list.getParent();
                        PsiMethod method = (PsiMethod) methodCall.getMethodExpression().resolve();
                        if (method != null) {
                            PsiParameter[] parameters = method.getParameterList().getParameters();
                            if (argIndex < parameters.length) {
                                addTypesByVariable(typesSet, parameters[argIndex], method.getContainingFile(), checkedVariables, flags | CHECK_USAGE,
                                        rangeToIgnore);
                            }
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private static PsiType getGenericElementType(PsiType collectionType) {
        if (collectionType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) collectionType;
            PsiType[] parameters = classType.getParameters();
            if (parameters.length == 1) {
                return parameters[0];
            }
        }
        return null;
    }

    @Nullable
    private static PsiType guessElementTypeFromReference(MethodPatternMap methodPatternMap,
                                                         PsiElement ref,
                                                         TextRange rangeToIgnore) {
        PsiElement refParent = ref.getParent();
        if (refParent instanceof PsiReferenceExpression){
            PsiReferenceExpression parentExpr = (PsiReferenceExpression)refParent;
            if (ref.equals(parentExpr.getQualifierExpression()) && parentExpr.getParent() instanceof PsiMethodCallExpression){
                String methodName = parentExpr.getReferenceName();
                PsiMethodCallExpression methodCall = (PsiMethodCallExpression)parentExpr.getParent();
                PsiExpression[] args = methodCall.getArgumentList().getExpressions();
                MethodPattern pattern = methodPatternMap.findPattern(methodName, args.length);
                if (pattern != null){
                    if (pattern.parameterIndex < 0){ // return value
                        if (methodCall.getParent() instanceof PsiTypeCastExpression &&
                                (rangeToIgnore == null || !rangeToIgnore.contains(methodCall.getTextRange()))) {
                            return ((PsiTypeCastExpression)methodCall.getParent()).getType();
                        }
                    }
                    else{
                        return args[pattern.parameterIndex].getType();
                    }
                }
            }
        }
        return null;
    }
}
