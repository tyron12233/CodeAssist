package com.tyron.completion.java.action.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.CompileTask;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.util.TreePath;

import java.nio.file.Path;

public class ActionContext {

    private final CompileTask mCompileTask;
    private final Path mCurrentFile;
    private final int mCursor;
    private final Diagnostic<? extends JavaFileObject> mDiagnostic;
    private final TreePath mCurrentPath;

    public ActionContext(CompileTask task, Path currentFile, int cursor, Diagnostic<?
            extends JavaFileObject> diagnostic, TreePath currentPath) {
        mCompileTask = task;
        mCurrentFile = currentFile;
        mCursor = cursor;
        mDiagnostic = diagnostic;
        mCurrentPath = currentPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompileTask getCompileTask() {
        return mCompileTask;
    }

    public TreePath getCurrentPath() {
        return mCurrentPath;
    }

    @NonNull
    public Path getCurrentFile() {
        return mCurrentFile;
    }

    /**
     * Used for actions that provide quick fixes
     *
     * @return the current diagnostic for the cursor position,
     * may be null if there is no diagnostic at the position
     */
    @Nullable
    public Diagnostic<? extends JavaFileObject> getDiagnostic() {
        return mDiagnostic;
    }

    public int getCursor() {
        return mCursor;
    }

    public static class Builder {

        private CompileTask task;
        private Path currentFile;
        private int cursor;
        private Diagnostic<? extends JavaFileObject> diagnostic;
        private TreePath currentPath;

        public Builder setCompileTask(CompileTask task) {
            this.task = task;
            return this;
        }

        public Builder setCurrentFile(Path file) {
            this.currentFile = file;
            return this;
        }

        public Builder setCursor(int cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder setDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
            this.diagnostic = diagnostic;
            return this;
        }

        public Builder setCurrentPath(TreePath path) {
            this.currentPath = path;
            return this;
        }

        public ActionContext build() {
            return new ActionContext(task, currentFile, cursor, diagnostic, currentPath);
        }
    }
}
