package com.tyron.code.ui.editor;

import com.tyron.builder.model.DiagnosticWrapper;

import java.util.List;

public interface DiagnosticsListener {

    void onDiagnosticsUpdate(List<DiagnosticWrapper> diagnostics);
}
