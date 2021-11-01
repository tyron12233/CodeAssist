package com.tyron.psi.completions.lang.java;

import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_LANG_STRING;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_ARRAYS;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_COLLECTION;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_LIST;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_SET;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_STREAM_BASE_STREAM;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.completions.lang.java.filter.TrueFilter;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;
import com.tyron.psi.util.DocumentUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.JBIterable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collector;

public class StreamConversion {

    static List<LookupElement> addToStreamConversion(PsiReferenceExpression ref, CompletionParameters parameters) {
        PsiExpression qualifier = ref.getQualifierExpression();
        if (qualifier == null) return Collections.emptyList();

        PsiType type = qualifier.getType();
        if (type instanceof PsiClassType) {
            PsiClass qualifierClass = ((PsiClassType)type).resolve();
            if (qualifierClass == null || InheritanceUtil.isInheritor(qualifierClass, JAVA_UTIL_STREAM_BASE_STREAM)) {
                return Collections.emptyList();
            }

            PsiMethod streamMethod = ContainerUtil.find(qualifierClass.findMethodsByName("stream", true), m -> !m.hasParameters());
            if (streamMethod == null ||
                    streamMethod.hasModifierProperty(PsiModifier.STATIC) ||
                    !PsiUtil.isAccessible(streamMethod, ref, null) ||
                    !InheritanceUtil.isInheritor(streamMethod.getReturnType(), JAVA_UTIL_STREAM_BASE_STREAM)) {
                return Collections.emptyList();
            }

            return generateStreamSuggestions(parameters, qualifier, qualifier.getText() + ".stream()", context -> {
                String space = ""; //getSpace(CodeStyle.getLanguageSettings(context.getFile()).SPACE_WITHIN_EMPTY_METHOD_CALL_PARENTHESES);
                DocumentUtils.insertString(context.getDocument(), context.getStartOffset(), "stream(" + space + ").");
            });
        }
        else if (type instanceof PsiArrayType) {
            String arraysStream = JAVA_UTIL_ARRAYS + ".stream";
            return generateStreamSuggestions(parameters, qualifier, arraysStream + "(" + qualifier.getText() + ")",
                    context -> wrapQualifiedIntoMethodCall(context, arraysStream));
        }

        return Collections.emptyList();
    }

    @NotNull
    private static List<LookupElement> generateStreamSuggestions(CompletionParameters parameters,
                                                                 PsiExpression qualifier,
                                                                 String changedQualifier,
                                                                 Consumer<InsertionContext> beforeInsertion) {
        String refText = changedQualifier + ".x";
        PsiExpression expr = PsiElementFactory.getInstance(qualifier.getProject()).createExpressionFromText(refText, qualifier);
        if (!(expr instanceof PsiReferenceExpression)) {
            return Collections.emptyList();
        }

        Set<LookupElement> streamSuggestions = ReferenceExpressionCompletionContributor
                .completeFinalReference(qualifier, (PsiReferenceExpression)expr, TrueFilter.INSTANCE,
                        PsiType.getJavaLangObject(qualifier.getManager(), qualifier.getResolveScope()),
                        parameters);
        return ContainerUtil.mapNotNull(streamSuggestions, e -> e);
//                ChainedCallCompletion.OBJECT_METHOD_PATTERN.accepts(e.getObject()) ? null : new StreamMethodInvocation(e, beforeInsertion));
    }

    private static void wrapQualifiedIntoMethodCall(@NotNull InsertionContext context, @NotNull String methodQualifiedName) {
        PsiFile file = context.getFile();
        PsiReferenceExpression ref =
                PsiTreeUtil.findElementOfClassAtOffset(file, context.getStartOffset(), PsiReferenceExpression.class, false);
        if (ref != null) {
            PsiElement qualifier = ref.getQualifier();
            if (qualifier != null) {
                TextRange range = qualifier.getTextRange();
                int startOffset = range.getStartOffset();

                String callSpace = ""; //getSpace(CodeStyle.getLanguageSettings(file).SPACE_WITHIN_METHOD_CALL_PARENTHESES);
                DocumentUtils.insertString(context.getDocument(), range.getEndOffset(), callSpace + ")");
                DocumentUtils.insertString(context.getDocument(), startOffset, methodQualifiedName + "(" + callSpace);

                context.commitDocument();
                Project project = context.getProject();
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                        file, startOffset, startOffset + methodQualifiedName.length());
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(context.getDocument());
            }
        }
    }

    static void addCollectConversion(PsiReferenceExpression ref, Collection<? extends ExpectedTypeInfo> expectedTypes, Consumer<? super LookupElement> consumer) {
        PsiClass collectors = JavaPsiFacade.getInstance(ref.getProject()).findClass(JAVA_UTIL_STREAM_COLLECTORS, ref.getResolveScope());
        if (collectors == null) return;

        PsiExpression qualifier = ref.getQualifierExpression();
        if (qualifier == null) {
            if (ref.getParent() instanceof PsiExpressionList && ref.getParent().getParent() instanceof PsiMethodCallExpression) {
                PsiReferenceExpression methodExpression = ((PsiMethodCallExpression)ref.getParent().getParent()).getMethodExpression();
                qualifier = methodExpression.getQualifierExpression();
                if ("collect".equals(methodExpression.getReferenceName()) && qualifier != null) {
                    suggestCollectorsArgument(expectedTypes, consumer, collectors, qualifier);
                }
            }
            return;
        }

        convertQualifierViaCollectors(ref, expectedTypes, consumer, qualifier, collectors);
    }

    private static void suggestCollectorsArgument(Collection<? extends ExpectedTypeInfo> expectedTypes, Consumer<? super LookupElement> consumer, PsiClass collectors, PsiExpression qualifier) {
        PsiType matchingExpectation = JBIterable.from(expectedTypes).map(ExpectedTypeInfo::getType)
                .find(t -> TypeConversionUtil.erasure(t).equalsToText(Collector.class.getName()));
        if (matchingExpectation == null) return;

        for (Pair<String, PsiType> pair : suggestCollectors(Arrays.asList(ExpectedTypesProvider.getExpectedTypes(qualifier, true)), qualifier)) {
            for (PsiMethod method : collectors.findMethodsByName(pair.first, false)) {
//                JavaMethodCallElement item = new JavaMethodCallElement(method, false, false);
//                item.setAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE);
//                item.setInferenceSubstitutorFromExpectedType(qualifier, matchingExpectation);
//                consumer.consume(PrioritizedLookupElement.withPriority(item, 1));
                consumer.consume(LookupElementBuilder.create(method.getName()));
            }
        }
    }

    private static void convertQualifierViaCollectors(PsiReferenceExpression ref,
                                                      Collection<? extends ExpectedTypeInfo> expectedTypes,
                                                      Consumer<? super LookupElement> consumer,
                                                      PsiExpression qualifier,
                                                      @NotNull PsiClass collectors) {
        for (Pair<String, PsiType> pair : suggestCollectors(expectedTypes, qualifier)) {
            if (collectors.findMethodsByName(pair.first, true).length == 0) continue;
//            consumer.consume(new CollectLookupElement(pair.first, pair.second, ref));
            consumer.consume(LookupElementBuilder.create(pair.first));
        }
    }

    // each pair of method name in Collectors class, and the corresponding collection type
    private static List<Pair<String, PsiType>> suggestCollectors(Collection<? extends ExpectedTypeInfo> expectedTypes, PsiExpression qualifier) {
        PsiType component = PsiUtil.substituteTypeParameter(qualifier.getType(), CommonClassNames.JAVA_UTIL_STREAM_STREAM, 0, true);
        if (component == null) return Collections.emptyList();

        JavaPsiFacade facade = JavaPsiFacade.getInstance(qualifier.getProject());
        PsiElementFactory factory = facade.getElementFactory();
        GlobalSearchScope scope = qualifier.getResolveScope();

        boolean joiningApplicable = InheritanceUtil.isInheritor(component, CharSequence.class.getName());

        PsiClass list = facade.findClass(JAVA_UTIL_LIST, scope);
        PsiClass set = facade.findClass(JAVA_UTIL_SET, scope);
        PsiClass collection = facade.findClass(JAVA_UTIL_COLLECTION, scope);
        PsiClass string = facade.findClass(JAVA_LANG_STRING, scope);
        if (list == null || set == null || collection == null || string == null) return Collections.emptyList();

        PsiType listType = null;
        PsiType setType = null;
        for (ExpectedTypeInfo info : expectedTypes) {
            PsiType type = info.getDefaultType();
            PsiClass expectedClass = PsiUtil.resolveClassInClassTypeOnly(type);
            PsiType expectedComponent = PsiUtil.extractIterableTypeParameter(type, true);
            if (expectedClass == null || expectedComponent == null || !TypeConversionUtil.isAssignable(expectedComponent, component)) continue;

            if (InheritanceUtil.isInheritorOrSelf(list, expectedClass, true)) {
                listType = type;
            }
            if (InheritanceUtil.isInheritorOrSelf(set, expectedClass, true)) {
                setType = type;
            }
        }

        if (listType == null) {
            listType = factory.createType(list, component);
        }

        if (setType == null) {
            setType = factory.createType(set, component);
        }

        List<Pair<String, PsiType>> result = new ArrayList<>();
        result.add(Pair.create("toList", listType));
        result.add(Pair.create("toUnmodifiableList", listType));
        result.add(Pair.create("toSet", setType));
        result.add(Pair.create("toUnmodifiableSet", setType));
        result.add(Pair.create("toCollection", factory.createType(collection, component)));
        if (joiningApplicable) {
            result.add(Pair.create("joining", factory.createType(string)));
        }
        return result;
    }


}
