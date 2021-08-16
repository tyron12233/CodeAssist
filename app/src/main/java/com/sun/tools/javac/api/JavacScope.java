/*
 * Copyright (c) 2006, 2008, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.api;


import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;



/**
 * Provides an implementation of Scope.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 *
 * @author Jonathan Gibbons;
 */
public class JavacScope implements com.sun.source.tree.Scope {
    protected final Env<AttrContext> env;

    /** Creates a new instance of JavacScope */
    JavacScope(Env<AttrContext> env) {
        env.getClass(); // null-check
        this.env = env;
    }

    public JavacScope getEnclosingScope() {
        if (env.outer != null && env.outer != env)
            return  new JavacScope(env.outer);
        else {
            // synthesize an outermost "star-import" scope
            return new JavacScope(env) {
                public boolean isStarImportScope() {
                    return true;
                }
                public JavacScope getEnclosingScope() {
                    return null;
                }
                public Iterable<? extends Element> getLocalElements() {
                    return env.toplevel.starImportScope.getElements();
                }
            };
        }
    }

    public TypeElement getEnclosingClass() {
        // hide the dummy class that javac uses to enclose the top level declarations
        return (env.outer == null || env.outer == env ? null : env.enclClass.sym);
    }

    public ExecutableElement getEnclosingMethod() {
        return (env.enclMethod == null ? null : env.enclMethod.sym);
    }

    public Iterable<? extends Element> getLocalElements() {
        return env.info.getLocalElements();
    }

    public Env<AttrContext> getEnv() {
        return env;
    }

    public boolean isStarImportScope() {
        return false;
    }

    public boolean equals(Object other) {
        if (other instanceof JavacScope) {
            JavacScope s = (JavacScope) other;
            return (env.equals(s.env)
                && isStarImportScope() == s.isStarImportScope());
        } else
            return false;
    }

    public int hashCode() {
        return env.hashCode() + (isStarImportScope() ? 1 : 0);
    }

    public String toString() {
        return "JavacScope[env=" + env + ",starImport=" + isStarImportScope() + "]";
    }
}
