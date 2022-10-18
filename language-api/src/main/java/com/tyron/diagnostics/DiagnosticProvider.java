package com.tyron.diagnostics;

import com.tyron.builder.project.api.Module;

import java.io.File;
import java.util.List;

import javax.tools.Diagnostic;

/**
 * Implementations may provide their own diagnostics for the specified file
 */
public interface DiagnosticProvider {

    List<? extends Diagnostic<?>> getDiagnostics(Module module, File file);
}
