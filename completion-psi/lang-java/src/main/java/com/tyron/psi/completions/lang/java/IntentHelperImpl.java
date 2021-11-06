package com.tyron.psi.completions.lang.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.codeStyle.IndentHelper;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.CompositeElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeUtil;

public class IntentHelperImpl extends IndentHelper {

    public static final int TOO_BIG_WALK_THRESHOLD = 450;
    public static final int INDENT_FACTOR = 10000; // "indent" is indent_level * INDENT_FACTOR + spaces

    @Override
    public int getIndent(@NotNull PsiFile psiFile, @NotNull ASTNode astNode) {
        return getIndent(psiFile, astNode, false);
    }

    @Override
    public int getIndent(@NotNull PsiFile psiFile, @NotNull ASTNode astNode, boolean b) {
        return getIndentInner(psiFile.getProject(), psiFile.getFileType(), astNode, true, 0);
    }

    protected int getIndentInner(Project project, FileType fileType, final ASTNode element, boolean includeNonSpace, int recursionLevel) {
        if (recursionLevel > TOO_BIG_WALK_THRESHOLD) return 0;

        if (element.getTreePrev() != null) {
            ASTNode prev = element.getTreePrev();
            ASTNode lastCompositePrev;
            while (prev instanceof CompositeElement && !TreeUtil.isStrongWhitespaceHolder(prev.getElementType())) {
                lastCompositePrev = prev;
                prev = prev.getLastChildNode();
                if (prev == null) { // element.prev is "empty composite"
                    return getIndentInner(project, fileType, lastCompositePrev, includeNonSpace, recursionLevel + 1);
                }
            }

            String text = prev.getText();
            int index = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));

            if (index >= 0) {
                return getIndent(project, fileType, text.substring(index + 1), includeNonSpace);
            }

            if (includeNonSpace) {
                return getIndentInner(project, fileType, prev, includeNonSpace, recursionLevel + 1) + getIndent(project, fileType, text, includeNonSpace);
            }


            ASTNode parent = prev.getTreeParent();
            ASTNode child = prev;
            while (parent != null) {
                if (child.getTreePrev() != null) break;
                child = parent;
                parent = parent.getTreeParent();
            }

            if (parent == null) {
                return getIndent(project, fileType, text, includeNonSpace);
            }
            else {
                return getIndentInner(project, fileType, prev, includeNonSpace, recursionLevel + 1);
            }
        }
        else {
            if (element.getTreeParent() == null) {
                return 0;
            }
            return getIndentInner(project, fileType, element.getTreeParent(), includeNonSpace, recursionLevel + 1);
        }
    }

    public static int getIndent(Project project, FileType fileType, String text, boolean includeNonSpace) {
     //   final CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(project);
        int i;
        for (i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') break;
        }
        i++;

        int spaceCount = 0;
        int tabCount = 0;
        for (int j = i; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c != '\t') {
                if (!includeNonSpace && c != ' ') break;
                spaceCount++;
            }
            else {
                tabCount++;
            }
        }

        if (tabCount == 0) return spaceCount;

        int tabSize = 4;//settings.getTabSize(fileType);
        int indentSize = 1;//settings.getIndentSize(fileType);
        if (indentSize <= 0) {
            indentSize = 1;
        }
        int indentLevel = tabCount * tabSize / indentSize;
        return indentLevel * INDENT_FACTOR + spaceCount;
    }
}
