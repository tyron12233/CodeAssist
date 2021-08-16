/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.source.util;

import java.lang.reflect.Method;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler.CompilationTask;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;

/**
 * Bridges JSR 199, JSR 269, and the Tree API.
 *
 * @author Peter von der Ah&eacute;
 */
public abstract class Trees {
    /**
     * Gets a Trees object for a given CompilationTask.
     * @param task the compilation task for which to get the Trees object
     * @throws IllegalArgumentException if the task does not support the Trees API.
     */
    public static Trees instance(CompilationTask task) {
        if (!task.getClass().getName().equals("com.sun.tools.javac.api.JavacTaskImpl"))
            throw new IllegalArgumentException();
        return getJavacTrees(CompilationTask.class, task);
    }

    /**
     * Gets a Trees object for a given ProcessingEnvironment.
     * @param env the processing environment for which to get the Trees object
     * @throws IllegalArgumentException if the env does not support the Trees API.
     */
    public static Trees instance(ProcessingEnvironment env) {
        if (!env.getClass().getName().equals("com.sun.tools.javac.processing.JavacProcessingEnvironment"))
            throw new IllegalArgumentException();
        return getJavacTrees(ProcessingEnvironment.class, env);
    }

    private static Trees getJavacTrees(Class<?> argType, Object arg) {
        try {
            ClassLoader cl = arg.getClass().getClassLoader();
            Class<?> c = Class.forName("com.sun.tools.javac.api.JavacTrees", false, cl);
            argType = Class.forName(argType.getName(), false, cl);
            Method m = c.getMethod("instance", new Class<?>[] { argType });
            return (Trees) m.invoke(null, new Object[] { arg });
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Gets a utility object for obtaining source positions.
     */
    public abstract SourcePositions getSourcePositions();

    /**
     * Gets the Tree node for a given Element.
     * Returns null if the node can not be found.
     */
    public abstract Tree getTree(Element element);

    /**
     * Gets the ClassTree node for a given TypeElement.
     * Returns null if the node can not be found.
     */
    public abstract ClassTree getTree(TypeElement element);

    /**
     * Gets the MethodTree node for a given ExecutableElement.
     * Returns null if the node can not be found.
     */
    public abstract MethodTree getTree(ExecutableElement method);

    /**
     * Gets the Tree node for an AnnotationMirror on a given Element.
     * Returns null if the node can not be found.
     */
    public abstract Tree getTree(Element e, AnnotationMirror a);

    /**
     * Gets the Tree node for an AnnotationValue for an AnnotationMirror on a given Element.
     * Returns null if the node can not be found.
     */
    public abstract Tree getTree(Element e, AnnotationMirror a, AnnotationValue v);

    /**
     * Gets the path to tree node within the specified compilation unit.
     */
    public abstract TreePath getPath(CompilationUnitTree unit, Tree node);

    /**
     * Gets the TreePath node for a given Element.
     * Returns null if the node can not be found.
     */
    public abstract TreePath getPath(Element e);

    /**
     * Gets the TreePath node for an AnnotationMirror on a given Element.
     * Returns null if the node can not be found.
     */
    public abstract TreePath getPath(Element e, AnnotationMirror a);

    /**
     * Gets the TreePath node for an AnnotationValue for an AnnotationMirror on a given Element.
     * Returns null if the node can not be found.
     */
    public abstract TreePath getPath(Element e, AnnotationMirror a, AnnotationValue v);

    /**
     * Gets the Element for the Tree node identified by a given TreePath.
     * Returns null if the element is not available.
     * @throws IllegalArgumentException is the TreePath does not identify
     * a Tree node that might have an associated Element.
     */
    public abstract Element getElement(TreePath path);

    /**
     * Gets the TypeMirror for the Tree node identified by a given TreePath.
     * Returns null if the TypeMirror is not available.
     * @throws IllegalArgumentException is the TreePath does not identify
     * a Tree node that might have an associated TypeMirror.
     */
    public abstract TypeMirror getTypeMirror(TreePath path);

    /**
     * Gets the Scope for the Tree node identified by a given TreePath.
     * Returns null if the Scope is not available.
     */
    public abstract Scope getScope(TreePath path);

    /**
     * Gets the doc comment, if any, for the Tree node identified by a given TreePath.
     * Returns null if no doc comment was found.
     */
    public abstract String getDocComment(TreePath path);

    /**
     * Checks whether a given type is accessible in a given scope.
     * @param scope the scope to be checked
     * @param type the type to be checked
     * @return true if {@code type} is accessible
     */
    public abstract boolean isAccessible(Scope scope, TypeElement type);

    /**
     * Checks whether the given element is accessible as a member of the given
     * type in a given scope.
     * @param scope the scope to be checked
     * @param member the member to be checked
     * @param type the type for which to check if the member is accessible
     * @return true if {@code member} is accessible in {@code type}
     */
    public abstract boolean isAccessible(Scope scope, Element member, DeclaredType type);

    /**
      * Gets the original type from the ErrorType object.
      * @param errorType The errorType for which we want to get the original type.
      * @return javax.lang.model.type.TypeMirror corresponding to the original type, replaced by the ErrorType.
      */
    public abstract TypeMirror getOriginalType(ErrorType errorType);

    /**
     * Prints a message of the specified kind at the location of the
     * tree within the provided compilation unit
     *
     * @param kind the kind of message
     * @param msg  the message, or an empty string if none
     * @param t    the tree to use as a position hint
     * @param root the compilation unit that contains tree
     */
    public abstract void printMessage(Diagnostic.Kind kind, CharSequence msg,
            Tree t,
            CompilationUnitTree root);

    /**
     * Gets the lub of an exception parameter declared in a catch clause.
     * @param tree the tree for the catch clause
     * @return The lub of the exception parameter
     */
    public abstract TypeMirror getLub(CatchTree tree);
}
