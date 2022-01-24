package com.tyron.code.lint;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.lint.api.Context;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.Lint;
import com.tyron.lint.api.Location;
import com.tyron.lint.api.Severity;
import com.tyron.lint.api.TextFormat;
import com.tyron.lint.client.LintClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DefaultLintClient extends LintClient {

    private final List<LintIssue> mIssues = new ArrayList<>();
    private final Lint mLint;
    private final JavaCompilerService mCompiler;

    public DefaultLintClient(JavaModule project) {
        mCompiler = CompilerService.getInstance()
                .getIndex(JavaCompilerProvider.KEY);
        mLint = new Lint(mCompiler, project, this);
    }

    public void scan(File file) {
        mIssues.clear();
        mLint.scanFile(file);
    }

    @Override
    public void report(@NonNull Context context, @NonNull Issue issue, @NonNull Severity severity, @Nullable Location location, @NonNull String message, @NonNull TextFormat format) {
        if (location != null) {
            Log.d("default lint client", "adding issue: " + issue.getId());
            mIssues.add(new LintIssue(issue, severity, location));
        }
    }

    public List<LintIssue> getReportedIssues() {
        return mIssues;
    }
}
