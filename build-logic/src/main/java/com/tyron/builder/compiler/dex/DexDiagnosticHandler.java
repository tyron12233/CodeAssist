package com.tyron.builder.compiler.dex;

import android.util.Log;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;

public class DexDiagnosticHandler implements DiagnosticsHandler {

    private static final String METAINF_ERROR = "Resource 'META-INF/MANIFEST.MF' already exists.";

    private final ILogger mLogger;

    public DexDiagnosticHandler(ILogger logger) {
        mLogger = logger;
    }

    @Override
    public void error(Diagnostic diagnostic) {
        mLogger.error(wrap(diagnostic, DiagnosticsLevel.ERROR));
    }

    @Override
    public void warning(Diagnostic diagnostic) {
        mLogger.warning(wrap(diagnostic, DiagnosticsLevel.WARNING));
    }

    @Override
    public void info(Diagnostic diagnostic) {
        mLogger.info(wrap(diagnostic, DiagnosticsLevel.INFO));
    }

    @Override
    public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel diagnosticsLevel,
                                                   Diagnostic diagnostic) {
        if (diagnostic.getDiagnosticMessage().equals(METAINF_ERROR)) {
            return DiagnosticsLevel.WARNING;
        }

        Log.d("DiagnosticHandler", diagnostic.getDiagnosticMessage());
        return diagnosticsLevel;
    }

    private DiagnosticWrapper wrap(Diagnostic diagnostic, DiagnosticsLevel level) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        wrapper.setMessage(diagnostic.getDiagnosticMessage());
        switch (level) {
            case WARNING:
                wrapper.setKind(javax.tools.Diagnostic.Kind.WARNING);
                break;
            case ERROR:
                wrapper.setKind(javax.tools.Diagnostic.Kind.ERROR);
                break;
            case INFO:
                wrapper.setKind(javax.tools.Diagnostic.Kind.NOTE);
                break;
        }
        return wrapper;
    }
}