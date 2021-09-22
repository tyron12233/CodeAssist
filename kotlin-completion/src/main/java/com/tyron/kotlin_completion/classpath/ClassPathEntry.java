package com.tyron.kotlin_completion.classpath;

import java.nio.file.Path;

public class ClassPathEntry {

    private final Path mCompiledJar;
    private final Path mSourceJar;

    public ClassPathEntry(Path compiledJar, Path sourceJar) {
        mCompiledJar = compiledJar;
        mSourceJar = sourceJar;
    }

    public Path getCompiledJar() {
        return mCompiledJar;
    }

    public Path getSourceJar() {
        return mSourceJar;
    }

    @Override
    public int hashCode() {
        return mCompiledJar.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ClassPathEntry) {
            if (((ClassPathEntry) obj).mCompiledJar.equals(this.mCompiledJar)) {
                return true;
            }
        }
        return false;
    }
}
