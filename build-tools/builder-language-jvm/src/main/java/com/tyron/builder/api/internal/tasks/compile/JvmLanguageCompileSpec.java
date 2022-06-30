package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.language.base.internal.compile.CompileSpec;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

public interface JvmLanguageCompileSpec extends CompileSpec {
    File getTempDir();

    void setTempDir(File tempDir);

    File getWorkingDir();

    void setWorkingDir(File workingDir);

    File getDestinationDir();

    void setDestinationDir(File destinationDir);

    Iterable<File> getSourceFiles();

    void setSourceFiles(Iterable<File> sourceFiles);

    List<File> getCompileClasspath();

    void setCompileClasspath(List<File> classpath);

    @Nullable
    Integer getRelease();

    void setRelease(@Nullable Integer release);

    @Nullable
    String getSourceCompatibility();

    void setSourceCompatibility(@Nullable String sourceCompatibility);

    @Nullable
    String getTargetCompatibility();

    void setTargetCompatibility(@Nullable String targetCompatibility);

    List<File> getSourceRoots();

    void setSourcesRoots(List<File> sourcesRoots);
}
