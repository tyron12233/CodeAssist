package com.tyron.psi.completions.lang.java.datafFlow;

import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_ARRAYS;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_LIST;

import com.tyron.psi.completions.lang.java.datafFlow.inliner.CallInliner;
import com.tyron.psi.completions.lang.java.util.CallMatcher;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

public class ControlFlowAnalyzer {

    private static final Logger LOG = Logger.getInstance(ControlFlowAnalyzer.class);
    private static final CallMatcher LIST_INITIALIZER = CallMatcher.anyOf(
            CallMatcher.staticCall(JAVA_UTIL_ARRAYS, "asList"),
            CallMatcher.staticCall(JAVA_UTIL_LIST, "of"));
    static final int MAX_UNROLL_SIZE = 3;
    private static final int MAX_ARRAY_INDEX_FOR_INITIALIZER = 32;

    /**
     * @param expression expression to test
     * @return true if some inliner may add constraints on the precise type of given expression
     */
    public static boolean inlinerMayInferPreciseType(PsiExpression expression) {
        return false;
//        return ContainerUtil.exists(INLINERS, inliner -> inliner.mayInferPreciseType(expression));
    }

//    private static final CallInliner[] INLINERS = {
//            new OptionalChainInliner(), new LambdaInliner(),
//            new StreamChainInliner(), new MapUpdateInliner(), new AssumeInliner(), new ClassMethodsInliner(),
//            new AssertAllInliner(), new BoxingInliner(), new SimpleMethodInliner(),
//            new TransformInliner(), new EnumCompareInliner()
//    };
}
