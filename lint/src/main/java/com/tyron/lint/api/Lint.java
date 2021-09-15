package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.model.Project;
import com.tyron.completion.JavaCompilerService;
import com.tyron.lint.JavaVisitor;
import com.tyron.lint.checks.JavaPerformanceDetector;
import com.tyron.lint.checks.SharedPrefsDetector;
import com.tyron.lint.client.Configuration;
import com.tyron.lint.client.IssueRegistry;
import com.tyron.lint.client.LintClient;
import com.tyron.lint.client.LintDriver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Lint {
    private final Project mProject;
    private final JavaCompilerService mCompiler;
    private final List<Detector> mDetectors;
    private final LintClient mClient;

    public Lint(JavaCompilerService compiler, Project project, LintClient client) {
        mCompiler = compiler;
        mProject = project;
        mClient = client;
        mDetectors = new ArrayList<>();

        registerDetector(new JavaPerformanceDetector());
        registerDetector(new SharedPrefsDetector());
    }

    public void scanFile(File file) {
        LintDriver driver = new LintDriver(new IssueRegistry() {
            @NonNull
            @Override
            public List<Issue> getIssues() {
                return Arrays.asList(
                        JavaPerformanceDetector.PAINT_ALLOC,
                        SharedPrefsDetector.ISSUE
                );
            }
        }, mClient);
        JavaContext context = new JavaContext(driver, mProject, file, new Configuration() {
            @Override
            public void ignore(@NonNull Context context, @NonNull Issue issue, @Nullable Location location, @NonNull String message) {

            }

            @Override
            public void setSeverity(@NonNull Issue issue, @Nullable Severity severity) {

            }
        });
        JavaVisitor visitor = new JavaVisitor(mCompiler, mDetectors);
        visitor.visitFile(context);
    }

    public void registerDetector(Detector detector) {
        mDetectors.add(detector);
    }
}
