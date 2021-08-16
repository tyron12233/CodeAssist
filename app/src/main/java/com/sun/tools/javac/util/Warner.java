/*
 * Copyright (c) 2003, 2010, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.util;

import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.EnumSet;

/**
 * An interface to support optional warnings, needed for support of
 * unchecked conversions and unchecked casts.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class Warner {
    public static final Warner noWarnings = new Warner();

    private DiagnosticPosition pos = null;
    protected boolean warned = false;
    private EnumSet<LintCategory> nonSilentLintSet = EnumSet.noneOf(LintCategory.class);
    private EnumSet<LintCategory> silentLintSet = EnumSet.noneOf(LintCategory.class);

    public DiagnosticPosition pos() {
        return pos;
    }

    public void warn(LintCategory lint) {
        nonSilentLintSet.add(lint);
    }

    public void silentWarn(LintCategory lint) {
        silentLintSet.add(lint);
    }

    public Warner(DiagnosticPosition pos) {
        this.pos = pos;
    }

    public boolean hasSilentLint(LintCategory lint) {
        return silentLintSet.contains(lint);
    }

    public boolean hasNonSilentLint(LintCategory lint) {
        return nonSilentLintSet.contains(lint);
    }

    public boolean hasLint(LintCategory lint) {
        return hasSilentLint(lint) ||
                hasNonSilentLint(lint);
    }

    public void clear() {
        nonSilentLintSet.clear();
        silentLintSet.clear();
        this.warned = false;
    }

    public Warner() {
        this(null);
    }
}
