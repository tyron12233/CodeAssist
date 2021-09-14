package com.tyron.lint.api;

import com.tyron.completion.JavaCompilerService;
import com.tyron.lint.JavaVisitor;
import com.tyron.lint.checks.JavaPerformanceDetector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Lint {

    private final JavaCompilerService mCompiler;
    private final List<Detector> mDetectors;

    public Lint(JavaCompilerService compiler) {
        mCompiler = compiler;
        mDetectors = new ArrayList<>();

        registerDetector(new JavaPerformanceDetector());
    }

    public void scanFile(File file) {
        JavaContext context = new JavaContext(file);
        JavaVisitor visitor = new JavaVisitor(mCompiler, mDetectors);
        visitor.visitFile(context);
    }

    public void registerDetector(Detector detector) {
        mDetectors.add(detector);
    }
}
