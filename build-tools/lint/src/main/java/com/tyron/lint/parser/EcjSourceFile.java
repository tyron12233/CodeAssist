package com.tyron.lint.parser;

import androidx.annotation.NonNull;

import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class EcjSourceFile extends CompilationUnit {

    private String mSource;

    public EcjSourceFile(@NonNull char[] source, @NonNull File file,
                         @NonNull String encoding) {
        super(source, file.getPath(), encoding);
        mSource = new String(source);
    }
    public EcjSourceFile(@NonNull String source, @NonNull File file,
                         @NonNull String encoding) {
        super(source.toCharArray(), file.getPath(), encoding);
        mSource = source;
    }
    public EcjSourceFile(@NonNull String source, @NonNull File file) {
        this(source, file, StandardCharsets.UTF_8.toString());
    }

    @NonNull
    public String getSource() {
        if (mSource == null) {
            mSource = new String(getContents());
        }
        return mSource;
    }
}
