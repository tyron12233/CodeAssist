package com.tyron.completion.psi.codeInsight.completion;

import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_ARRAYS;
import static org.jetbrains.kotlin.com.intellij.psi.CommonClassNames.JAVA_UTIL_STREAM_BASE_STREAM;

import com.tyron.completion.CompletionParameters;
import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collections;
import java.util.List;

public class StreamConversion {

//    static List<LookupElement> addToStreamConversion(PsiReferenceExpression ref, CompletionParameters parameters) {
//        PsiExpression qualifier = ref.getQualifierExpression();
//        if (qualifier == null) {
//            return Collections.emptyList();
//        }
//        PsiType type = qualifier.getType();
//        if (type instanceof PsiClassType) {
//            PsiClass qualifierClass = ((PsiClassType)type).resolve();
//            if (qualifierClass == null || InheritanceUtil.isInheritor(qualifierClass, JAVA_UTIL_STREAM_BASE_STREAM)) {
//                return Collections.emptyList();
//            }
//
//            PsiMethod streamMethod = ContainerUtil.find(qualifierClass.findMethodsByName("stream", true), m -> !m.hasParameters());
//            if (streamMethod == null ||
//                streamMethod.hasModifierProperty(PsiModifier.STATIC) ||
//                !PsiUtil.isAccessible(streamMethod, ref, null) ||
//                !InheritanceUtil.isInheritor(streamMethod.getReturnType(), JAVA_UTIL_STREAM_BASE_STREAM)) {
//                return Collections.emptyList();
//            }
//
//            return generateStreamSuggestions(parameters, qualifier, qualifier.getText() + ".stream()", context -> {
//                String space = getSpace(CodeStyle.getLanguageSettings(context.getFile()).SPACE_WITHIN_EMPTY_METHOD_CALL_PARENTHESES);
//                context.getDocument().insertString(context.getStartOffset(), "stream(" + space + ").");
//            });
//        }
//        else if (type instanceof PsiArrayType) {
//            String arraysStream = JAVA_UTIL_ARRAYS + ".stream";
//            return generateStreamSuggestions(parameters, qualifier, arraysStream + "(" + qualifier.getText() + ")",
//                    context -> wrapQualifiedIntoMethodCall(context, arraysStream));
//        }
//
//        return Collections.emptyList();
//    }
}
