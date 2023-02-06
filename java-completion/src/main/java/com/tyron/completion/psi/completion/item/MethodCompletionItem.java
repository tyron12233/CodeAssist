package com.tyron.completion.psi.completion.item;

import androidx.annotation.NonNull;

import com.tyron.completion.model.CompletionItemWithMatchLevel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;

import java.util.Arrays;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

public class MethodCompletionItem extends SimplePsiCompletionItem {

    private final PsiMethod method;
    private final PsiElement position;

    public MethodCompletionItem(PsiMethod method, PsiElement position) {
        super(method, method.getName(), position);

        addFilterText(method.getName());

        PsiType returnType = method.getReturnType();
        if (returnType == null) {
            desc("Unknown");
        } else {
            desc(returnType.getPresentableText());
        }

        label(getMethodLabel(method));


        this.method = method;
        this.position = position;
    }

    @Override
    public void performCompletion(@NonNull @NotNull CodeEditor editor,
                                  @NonNull @NotNull Content text,
                                  int line,
                                  int column) {
        super.performCompletion(editor, text, line, column);

        boolean keepCursorInParen = false;
        int parametersCount = method.getParameterList().getParametersCount();
        if (parametersCount == 0) {
            editor.insertText("()", 2);
        } else {
            editor.insertText("(", 1);
            keepCursorInParen = true;
        }

        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            if (returnType.equalsToText("void")) {
                int selectionOffset = keepCursorInParen ? 0 : 1;
                editor.insertText(";", selectionOffset);
            }
        }
    }

    public static String getMethodLabel(PsiMethod method) {
        String name = method.getName();
        String parameters = "";
        if (method.hasParameters()) {
            PsiParameterList parameterList = method.getParameterList();
            parameters = Arrays.stream(parameterList.getParameters())
                    .map(PsiParameter::getType)
                    .map(PsiType::getPresentableText)
                    .collect(Collectors.joining(", "));
        }
        return name + "(" + parameters + ")";
    }
}
