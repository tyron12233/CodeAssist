package com.tyron.lint.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.lint.api.Context;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.Location;

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;

import java.util.List;

public abstract class JavaParser {
    public static final String TYPE_OBJECT = "java.lang.Object";        //$NON-NLS-1$
    public static final String TYPE_STRING = "java.lang.String";        //$NON-NLS-1$
    public static final String TYPE_INT = "int";                        //$NON-NLS-1$
    public static final String TYPE_LONG = "long";                      //$NON-NLS-1$
    public static final String TYPE_CHAR = "char";                      //$NON-NLS-1$
    public static final String TYPE_FLOAT = "float";                    //$NON-NLS-1$
    public static final String TYPE_DOUBLE = "double";                  //$NON-NLS-1$
    public static final String TYPE_BOOLEAN = "boolean";                //$NON-NLS-1$
    public static final String TYPE_SHORT = "short";                    //$NON-NLS-1$
    public static final String TYPE_BYTE = "byte";                      //$NON-NLS-1$
    public static final String TYPE_NULL = "null";                      //$NON-NLS-1$

    /**
     * Prepare to parse the given contexts. This method will be called before
     * a series of {@link #parseJava(JavaContext)} calls, which allows some
     * parsers to do up front global computation in case they want to more
     * efficiently process multiple files at the same time. This allows a single
     * type-attribution pass for example, which is a lot more efficient than
     * performing global type analysis over and over again for each individual
     * file
     *
     * @param contexts a list of contexts to be parsed
     */
    public abstract void prepareJavaParse(@NonNull List<JavaContext> contexts);

    /**
     * Parse the file pointed to by the given context.
     *
     * @param context the context pointing to the file to be parsed, typically
     *            via {@link Context#getContents()} but the file handle (
     *            {@link Context#file} can also be used to map to an existing
     *            editor buffer in the surrounding tool, etc)
     * @return the compilation unit node for the file
     */
    @Nullable
    public abstract PsiJavaFile parseJavaToPsi(@NonNull JavaContext context);

    /**
     * Returns a {@link Location} for the given element
     *
     * @param context information about the file being parsed
     * @param element the element to create a location for
     * @return a location for the given node
     */
    @SuppressWarnings("MethodMayBeStatic") // subclasses may want to override/optimize
    @NonNull
    public Location getLocation(@NonNull JavaContext context, @NonNull PsiElement element) {
        TextRange range = element.getTextRange();
        return Location.create(context.file, context.getContents(), range.getStartOffset(),
                range.getEndOffset());
    }

    /**
     * Returns a {@link Location} for the given node range (from the starting offset of the first
     * node to the ending offset of the second node).
     *
     * @param from      the AST node to get a starting location from
     * @param fromDelta Offset delta to apply to the starting offset
     * @param to        the AST node to get a ending location from
     * @param toDelta   Offset delta to apply to the ending offset
     * @return a location for the given node
     */
    @SuppressWarnings("MethodMayBeStatic") // subclasses may want to override/optimize
    @NonNull
    public Location getRangeLocation(
            @NonNull JavaContext context,
            @NonNull PsiElement from,
            int fromDelta,
            @NonNull PsiElement to,
            int toDelta) {
        String contents = context.getContents();
        int start = Math.max(0, from.getTextRange().getStartOffset() + fromDelta);
        int end = Math.min(contents == null ? Integer.MAX_VALUE : contents.length(),
                to.getTextRange().getEndOffset() + toDelta);
        return Location.create(context.file, contents, start, end);
    }

    /**
     * Returns a {@link Location} for the given node. This attempts to pick a shorter
     * location range than the entire node; for a class or method for example, it picks
     * the name node (if found). For statement constructs such as a {@code switch} statement
     * it will highlight the keyword, etc.
     *
     * @param context information about the file being parsed
     * @param element the node to create a location for
     * @return a location for the given node
     */
    @NonNull
    public Location getNameLocation(@NonNull JavaContext context, @NonNull PsiElement element) {
        PsiElement nameNode = JavaContext.findNameElement(element);
        if (nameNode != null) {
            element = nameNode;
        } else {
            /* TODO:
            if (element instanceof PsiSwitchStatement
                    || element instanceof PsiForStatement
                    || element instanceof PsiIfStatement
                    || element instanceof PsiWhileStatement
                    || element instanceof PsiThrowStatement
                    || element instanceof PsiReturnStatement) {
                Location location = getLocation(context, element);
                Position start = location.getStart();
                if (start != null) {
                    // Lint doesn't want to highlight the entire statement/block associated
                    // with this node, it wants to just highlight the keyword.
                    // TODO: Figure out how to find the keyword length; for Lombok
                    // we could guess based on the node type, but we can't do
                    // that here.
                    int length = ?;
                    return Location.create(location.getFile(), start,
                            new DefaultPosition(start.getLine(), start.getColumn() + length,
                                    start.getOffset() + length));
                }
            }
            */
        }
        return getLocation(context, element);
    }

    /**
     * Dispose any data structures held for the given context.
     * @param context information about the file previously parsed
     * @param compilationUnit the compilation unit being disposed
     */
    public void dispose(@NonNull JavaContext context, @NonNull PsiJavaFile compilationUnit) {
    }

    /**
     * Dispose any remaining data structures held for all contexts.
     * Typically frees up any resources allocated by
     * {@link #prepareJavaParse(List)}
     */
    public void dispose() {
    }



}
