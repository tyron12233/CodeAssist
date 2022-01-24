package com.tyron.lint.api;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.lint.JavaVisitor;
import com.tyron.lint.checks.CallSuperDetector;
import com.tyron.lint.checks.JavaPerformanceDetector;
import com.tyron.lint.checks.SharedPrefsDetector;
import com.tyron.lint.checks.ToastDetector;
import com.tyron.lint.client.Configuration;
import com.tyron.lint.client.IssueRegistry;
import com.tyron.lint.client.LintClient;
import com.tyron.lint.client.LintDriver;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Lint {
    private final JavaModule mProject;
    private final JavaCompilerService mCompiler;
    private final List<Detector> mDetectors;
    private final LintClient mClient;

    public Lint(JavaCompilerService compiler, JavaModule project, LintClient client) {
        mCompiler = compiler;
        mProject = project;
        mClient = client;
        mDetectors = new ArrayList<>();

        registerDetector(new JavaPerformanceDetector());
        registerDetector(new SharedPrefsDetector());
        registerDetector(new CallSuperDetector());
    }

    public void scanFile(File file) {
        Instant start = Instant.now();
        LintDriver driver = new LintDriver(new IssueRegistry() {
            @NonNull
            @Override
            public List<Issue> getIssues() {
                return Arrays.asList(
                        JavaPerformanceDetector.PAINT_ALLOC,
                        SharedPrefsDetector.ISSUE,
                        CallSuperDetector.ISSUE,
                        ToastDetector.ISSUE
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

        Log.d("Lint", "Scanning took " + Duration.between(start, Instant.now()).toMillis() + " ms");
    }

    public void registerDetector(Detector detector) {
        mDetectors.add(detector);
    }
}
