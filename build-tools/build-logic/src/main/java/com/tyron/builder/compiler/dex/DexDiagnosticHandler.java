package com.tyron.builder.compiler.dex;

import android.util.Log;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.errors.DuplicateTypesDiagnostic;
import com.android.tools.r8.origin.Origin;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.api.JavaModule;

import java.io.File;
import java.util.Collection;

public class DexDiagnosticHandler implements DiagnosticsHandler {

    private static final String METAINF_ERROR = "Resource 'META-INF/MANIFEST.MF' already exists.";
    private static final String LIBRARY_DIR = "build/libs/";
    private static final String CLASSES_DIR = "build/intermediate/classes/";

    private final ILogger mLogger;
    private final JavaModule mModule;

    public DexDiagnosticHandler(ILogger logger, JavaModule module) {
        mLogger = logger;
        mModule = module;
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
        if (diagnostic instanceof DuplicateTypesDiagnostic) {
            DuplicateTypesDiagnostic d = (DuplicateTypesDiagnostic) diagnostic;

            String typeName = d.getType().getTypeName();

            String message = "" +
                    "The type " + typeName + " is defined multiple times in multiple jars. " +
                    "There can only be one class with the same package and name. Please locate the " +
                    "following jars and determine which class is appropriate to keep. \n\n";
            message += "Files: \n";
            message += printDuplicateOrigins(d.getOrigins());
            wrapper.setMessage(message);
        } else {
            wrapper.setMessage(diagnostic.getDiagnosticMessage());
        }
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

    private String printDuplicateOrigins(Collection<Origin> origins) {
        StringBuilder builder = new StringBuilder();

        for (Origin origin : origins) {
            builder.append("\n");
            if (isClassFile(origin)) {
                builder.append("Class/Source file");
                builder.append("\n");
                builder.append("path: ");
                builder.append(getClassFile(origin));
            } else if (isJarFile(origin)) {
                builder.append("Jar file");
                builder.append("\n");
                builder.append("path: ");
                builder.append(getLibraryFile(origin));
            } else {
                builder.append("path: ");
                builder.append(origin.part());
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private boolean isJarFile(Origin origin) {
        return origin.part().contains(LIBRARY_DIR);
    }

    private String getLibraryFile(Origin origin) {
        String part = origin.part();
        File file = new File(part);
        if (!file.exists()) {
            return part;
        }

        File parent = file.getParentFile();
        if (parent == null) {
            return part;
        }

        String hash = parent.getName();
        Library library = mModule.getLibrary(hash);
        if (library == null) {
            return part;
        }

        if (library.isDependency()) {
            return library.getDeclaration();
        }

        return library.getSourceFile().getAbsolutePath();
    }

    private boolean isClassFile(Origin origin) {
        return origin.part().contains(CLASSES_DIR);
    }

    private String getClassFile(Origin origin) {
        String part = origin.part();
        int index = part.indexOf(CLASSES_DIR);
        if (index == -1) {
            return part;
        }
        int endIndex = part.length();
        if (part.endsWith(".dex")) {
            endIndex -= ".dex".length();
        }
        index += CLASSES_DIR.length();
        String fqn = part.substring(index, endIndex)
                .replace('/', '.');

        File javaFile = mModule.getJavaFile(fqn);
        if (javaFile == null) {
            return part;
        }

        return javaFile.getAbsolutePath();
    }
}