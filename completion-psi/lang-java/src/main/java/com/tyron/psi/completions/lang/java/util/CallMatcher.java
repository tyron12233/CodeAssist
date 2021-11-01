package com.tyron.psi.completions.lang.java.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import one.util.streamex.StreamEx;

/**
 * This interface represents a condition upon method call
 *
 * @author Tagir Valeev
 */
public interface CallMatcher extends Predicate<PsiMethodCallExpression> {

    /**
     * @return distinct names of the methods for which this matcher may return true. For any other method it guaranteed to return false
     */
    Stream<String> names();

    @Contract(value = "null -> false", pure = true)
    boolean methodReferenceMatches(PsiMethodReferenceExpression methodRef);

    @Override
    @Contract(value = "null -> false", pure = true)
    boolean test(@Nullable PsiMethodCallExpression call);

    @Contract(value = "null -> false", pure = true)
    boolean methodMatches(@Nullable PsiMethod method);

    /**
     * Returns true if the supplied expression is (possibly parenthesized) method call which matches this matcher
     *
     * @param expression expression to test
     * @return true if the supplied expression matches this matcher
     */
    @Contract(value = "null -> false", pure = true)
    default boolean matches(@Nullable PsiExpression expression) {
        expression = PsiUtil.skipParenthesizedExprDown(expression);
        return expression instanceof PsiMethodCallExpression && test((PsiMethodCallExpression)expression);
    }

    /**
     * Creates a matcher which matches an instance method having one of supplied names which class (or any of superclasses) is className
     *
     * @param className fully-qualified class name
     * @param methodNames names of the methods
     * @return a new matcher
     */
    @Contract(pure = true)
    static Simple instanceCall(@NotNull @NonNls String className, @NonNls String... methodNames) {
        return new Simple(className, Set.of(methodNames), null, CallType.INSTANCE);
    }

    /**
     * Returns a new matcher which will return true if any of supplied matchers return true
     *
     * @param matchers matchers to delegate to
     * @return a new matcher
     */
    static CallMatcher anyOf(CallMatcher... matchers) {
        return new CallMatcher() {
            @Override
            public Stream<String> names() {
                return Stream.of(matchers).flatMap(CallMatcher::names).distinct();
            }

            @Override
            public boolean methodReferenceMatches(PsiMethodReferenceExpression methodRef) {
                for (CallMatcher m : matchers) {
                    if (m.methodReferenceMatches(methodRef)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean methodMatches(PsiMethod method) {
                for (CallMatcher m : matchers) {
                    if (m.methodMatches(method)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean test(PsiMethodCallExpression call) {
                for (CallMatcher m : matchers) {
                    if (m.test(call)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String toString() {
                return Stream.of(matchers).map(CallMatcher::toString).collect(Collectors.joining(" or ", "{", "}"));
            }
        };
    }

    /**
     * Creates a matcher which matches an instance method having one of supplied names which class is exactly a className
     *
     * @param className fully-qualified class name
     * @param methodNames names of the methods
     * @return a new matcher
     */
    @Contract(pure = true)
    static Simple exactInstanceCall(@NotNull @NonNls String className, @NonNls String... methodNames) {
        return new Simple(className, Set.of(methodNames), null, CallType.EXACT_INSTANCE);
    }

    /**
     * Creates a matcher which matches a static method having one of supplied names which class is className
     *
     * @param className fully-qualified class name
     * @param methodNames names of the methods
     * @return a new matcher
     */
    @Contract(pure = true)
    static Simple staticCall(@NotNull @NonNls String className, @NonNls String... methodNames) {
        return new Simple(className, Set.of(methodNames), null, CallType.STATIC);
    }

    final class Simple implements CallMatcher {

        static final Simple ENUM_VALUES = new Simple("", Collections.singleton("values"), ArrayUtilRt.EMPTY_STRING_ARRAY, CallType.ENUM_STATIC);
        static final Simple ENUM_VALUE_OF =
                new Simple("", Collections.singleton("valueOf"), new String[]{CommonClassNames.JAVA_LANG_STRING}, CallType.ENUM_STATIC);
        private final @NotNull
        String myClassName;
        private final @NotNull
        Set<String> myNames;
        private final String[] myParameters;
        private final CallType myCallType;

        private Simple(@NotNull String className, @NotNull Set<String> names, String[] parameters, CallType callType) {
            myClassName = className;
            myNames = names;
            myParameters = parameters;
            myCallType = callType;
        }

        /**
         * Creates a new matcher which in addition to current matcher checks the number of parameters of the called method
         *
         * @param count expected number of parameters
         * @return a new matcher
         * @throws IllegalStateException if this matcher is already limited to parameters count or types
         */
        @Contract(pure = true)
        public Simple parameterCount(int count) {
            if (myParameters != null) {
                throw new IllegalStateException("Parameter count is already set to " + count);
            }
            return new Simple(myClassName, myNames, count == 0 ? ArrayUtilRt.EMPTY_STRING_ARRAY : new String[count], myCallType);
        }

        /**
         * Creates a new matcher which in addition to current matcher checks the number of parameters of the called method
         * and their types
         *
         * @param types textual representation of parameter types (may contain null to ignore checking parameter type of specific argument)
         * @return a new matcher
         * @throws IllegalStateException if this matcher is already limited to parameters count or types
         */
        @Contract(pure = true)
        public Simple parameterTypes(@NonNls String... types) {
            if (myParameters != null) {
                throw new IllegalStateException("Parameters are already registered");
            }
            return new Simple(myClassName, myNames, types.length == 0 ? ArrayUtilRt.EMPTY_STRING_ARRAY : types.clone(), myCallType);
        }

        @Override
        public Stream<String> names() {
            return myNames.stream();
        }

        @Override
        public boolean methodReferenceMatches(PsiMethodReferenceExpression methodRef) {
            return false;
        }

        @Contract(value = "null -> false", pure = true)
        @Override
        public boolean test(PsiMethodCallExpression call) {
            if (call == null) return false;
            String name = call.getMethodExpression().getReferenceName();
            if (name == null || !myNames.contains(name)) return false;
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (myParameters != null && myParameters.length > 0) {
                if (args.length < myParameters.length - 1) return false;
            }
            PsiMethod method = call.resolveMethod();
            if (method == null) return false;
            PsiParameterList parameterList = method.getParameterList();
            int count = parameterList.getParametersCount();
            if (count > args.length + 1 || (!MethodCallUtils.isVarArgCall(call) && count != args.length)) {
                return false;
            }
            return methodMatches(method);
        }


        @Override
        @Contract(value = "null -> false", pure = true)
        public boolean methodMatches(PsiMethod method) {
            if (method == null) return false;
            if (!myNames.contains(method.getName())) return false;
            PsiClass aClass = method.getContainingClass();
            if (aClass == null) return false;
            return myCallType.matches(aClass, myClassName, method.hasModifierProperty(PsiModifier.STATIC)) &&
                    parametersMatch(method.getParameterList());
        }

        private boolean parametersMatch(@NotNull PsiParameterList parameterList) {
            if (myParameters == null) return true;
            if (myParameters.length != parameterList.getParametersCount()) return false;
            return StreamEx.zip(myParameters, parameterList.getParameters(),
                    Simple::parameterTypeMatches).allMatch(Boolean.TRUE::equals);
        }

        private static boolean parameterTypeMatches(String type, PsiParameter parameter) {
            if (type == null) return true;
            PsiType psiType = parameter.getType();
            return psiType.equalsToText(type) || classNameEquals(psiType, type);
        }

        private static boolean classNameEquals(@Nullable PsiType type, @NotNull String qualifiedClassName) {
            if (!(type instanceof PsiClassType)) return false;
            PsiClassType classType = (PsiClassType)type;
            String className = classType.getClassName();
            if (className == null || !qualifiedClassName.endsWith(className)) return false;
            PsiClass psiClass = classType.resolve();
            if (psiClass == null) return false;
            return qualifiedClassName.equals(psiClass.getQualifiedName());
        }
    }

    enum CallType {
        STATIC {
            @Override
            boolean matches(PsiClass aClass, String className, boolean isStatic) {
                return isStatic && className.equals(aClass.getQualifiedName());
            }
        },
        ENUM_STATIC {
            @Override
            boolean matches(PsiClass aClass, String className, boolean isStatic) {
                return isStatic && aClass.isEnum();
            }
        },
        INSTANCE {
            @Override
            boolean matches(PsiClass aClass, String className, boolean isStatic) {
                return !isStatic && InheritanceUtil.isInheritor(aClass, className);
            }
        },
        EXACT_INSTANCE {
            @Override
            boolean matches(PsiClass aClass, String className, boolean isStatic) {
                return !isStatic && className.equals(aClass.getQualifiedName());
            }
        };

        abstract boolean matches(PsiClass aClass, String className, boolean isStatic);
    }
}
