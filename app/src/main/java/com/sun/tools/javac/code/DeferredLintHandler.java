/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.code;

import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.ListBuffer;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class DeferredLintHandler {
    protected static final Context.Key<DeferredLintHandler> deferredLintHandlerKey =
        new Context.Key<DeferredLintHandler>();

    public static DeferredLintHandler instance(Context context) {
        DeferredLintHandler instance = context.get(deferredLintHandlerKey);
        if (instance == null)
            instance = new DeferredLintHandler(context);
        return instance;
    }

    protected DeferredLintHandler(Context context) {
        context.put(deferredLintHandlerKey, this);
    }

    private DeferredLintHandler() {}

    public interface LintLogger {
        void report();
    }

    private DiagnosticPosition currentPos;
    private Map<DiagnosticPosition, ListBuffer<LintLogger>> loggersQueue = new HashMap<DiagnosticPosition, ListBuffer<LintLogger>>();

    public void report(LintLogger logger) {
        ListBuffer<LintLogger> loggers = loggersQueue.get(currentPos);
        Assert.checkNonNull(loggers);
        loggers.append(logger);
    }

    public void flush(DiagnosticPosition pos) {
        ListBuffer<LintLogger> loggers = loggersQueue.get(pos);
        if (loggers != null) {
            for (LintLogger lintLogger : loggers) {
                lintLogger.report();
            }
            loggersQueue.remove(pos);
        }
    }

    public DeferredLintHandler setPos(DiagnosticPosition currentPos) {
        this.currentPos = currentPos;
        loggersQueue.put(currentPos, ListBuffer.<LintLogger>lb());
        return this;
    }

    public static final DeferredLintHandler immediateHandler = new DeferredLintHandler() {
        @Override
        public void report(LintLogger logger) {
            logger.report();
        }
    };
}
