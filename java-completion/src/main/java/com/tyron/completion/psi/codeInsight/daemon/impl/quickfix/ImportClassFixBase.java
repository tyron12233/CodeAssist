package com.tyron.completion.psi.codeInsight.daemon.impl.quickfix;

import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.psi.codeInsight.ImportFilter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;

public class ImportClassFixBase {

    public static boolean qualifiedNameAllowsAutoImport(@NotNull PsiFile placeFile, @NotNull PsiClass aClass) {
        if (JavaCompletionUtil.isInExcludedPackage(aClass, false)) {
            return false;
        }
        String qName = aClass.getQualifiedName();
        if (qName != null) { //filter local classes
            if (qName.indexOf('.') == -1 || !PsiNameHelper.getInstance(placeFile.getProject()).isQualifiedName(qName)) return false;
            return ImportFilter.shouldImport(placeFile, qName);
        }
        return false;
    }
}
