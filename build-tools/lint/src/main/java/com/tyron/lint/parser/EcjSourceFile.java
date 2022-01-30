package com.tyron.lint.parser;

import static com.tyron.builder.compiler.manifest.SdkConstants.UTF_8;

import androidx.annotation.NonNull;

import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;

import java.io.File;

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
        this(source, file, UTF_8);
    }

    @NonNull
    public String getSource() {
        if (mSource == null) {
            mSource = new String(getContents());
        }
        return mSource;
    }
}
