package com.tyron.completion.java.action.api;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.rewrite.Rewrite;
import com.tyron.completion.java.util.ThreadUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.util.TreePath;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ActionContext {

    private final Context mContext;
    private final JavaCompilerService mCompiler;
    private final CompileTask mCompileTask;
    private final Path mCurrentFile;
    private final EditorInterface mEditor;
    private final int mCursor;
    private final Diagnostic<? extends JavaFileObject> mDiagnostic;
    private final TreePath mCurrentPath;
    private final Menu mMenu;
    private final Map<String, Integer> mIds = new HashMap<>();
    private int mIdCount = 40;

    public ActionContext(Context context, JavaCompilerService compiler, EditorInterface editor, Menu menu,
                         CompileTask task, Path currentFile, int cursor, Diagnostic<?
            extends JavaFileObject> diagnostic, TreePath currentPath) {
        mContext = context;
        mEditor = editor;
        mCompiler = compiler;
        mMenu = menu;
        mCompileTask = task;
        mCurrentFile = currentFile;
        mCursor = cursor;
        mDiagnostic = diagnostic;
        mCurrentPath = currentPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Do not use this compile task outside addMenu(), this may be outdated and may return null.
     * Use {@code getCompiler#compile(Path file)} to get a new instance of CompileTask
     *
     * example, do not use this inside an onMenuItemClickEvent.
     */
    public CompileTask getCompileTask() {
        return mCompileTask;
    }

    /**
     * The TreePath represented by the current cursor position
     */
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


    /**
     * Get the an id for a specified key, useful for adding the same menu on
     * an existing submenu
     *
     * @param name Key name for the id
     * @return the id of the menu
     */
    public Integer getMenuId(String name) {
        if (!mIds.containsKey(name)) {
            mIds.put(name, mIdCount++);
        }
        return mIds.get(name);
    }

    public MenuItem addMenu(String groupId, String title) {
        int id = getMenuId(groupId);
        return mMenu.add(id, Menu.NONE, Menu.NONE, title);
    }

    public SubMenu addSubMenu(String groupId, String name) {
        int id = getMenuId(groupId);
        return mMenu.addSubMenu(id, Menu.NONE, Menu.NONE, "JUST TESTING");
    }

    /**
     * Performs an action on the background thread and dispatches them on the UI thread after.
     * @param action action to perform
     */
    public void performAction(Action action) {
        ThreadUtil.runOnBackgroundThread(() -> {
            Rewrite rewrite = action.getRewrite();
            Map<Path, TextEdit[]> rewrites = rewrite.rewrite(mCompiler);
            ThreadUtil.runOnUiThread(() -> {
                mEditor.beginBatchEdit();
                rewrites.forEach((k, v) -> {
                    if (k.equals(mCurrentFile)) {
                        for (TextEdit edit : v) {
                            applyTextEdit(edit);
                        }
                    }
                });
                mEditor.endBatchEdit();
            });
        });
    }

    private void applyTextEdit(TextEdit edit) {
        int startFormat;
        int endFormat;
        Range range = edit.range;
        if (range.start.line == -1 && range.start.column == -1 || (range.end.line == -1 && range.end.column == -1)) {
            EditorInterface.CharPositionWrapper startChar =
                    mEditor.getCharPosition((int) range.start.start);
            EditorInterface.CharPositionWrapper endChar =
                    mEditor.getCharPosition((int) range.end.end);

            if (range.start.start == range.end.end) {
                mEditor.insert(startChar.line, startChar.column, edit.newText);
            } else {
                mEditor.replace(startChar.line, startChar.column, endChar.line, endChar.column,
                        edit.newText);
            }

            startFormat = (int) range.start.start;
        } else {
            if (range.start.equals(range.end)) {
                mEditor.insert(range.start.line, range.start.column, edit.newText);
            } else {
                mEditor.replace(range.start.line, range.start.column, range.end.line,
                        range.end.column, edit.newText);
            }
            startFormat = mEditor.getCharIndex(range.start.line, range.start.column);
        }
        endFormat = startFormat + edit.newText.length();

        if (startFormat < endFormat) {
            if (edit.needFormat) {
                mEditor.formatCodeAsync(startFormat, endFormat);
            }
        }
    }

    public Context getContext() {
        return mContext;
    }

    public JavaCompilerService getCompiler() {
        return mCompiler;
    }

    public static class Builder {

        private CompileTask task;
        private Path currentFile;
        private int cursor;
        private Diagnostic<? extends JavaFileObject> diagnostic;
        private TreePath currentPath;
        private Menu menu;
        private EditorInterface editor;
        private JavaCompilerService compiler;
        private Context context;

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

        public Builder setMenu(Menu menu) {
            this.menu = menu;
            return this;
        }

        public Builder setCompiler(JavaCompilerService compiler) {
            this.compiler = compiler;
            return this;
        }

        public Builder setEditorInterface(EditorInterface editor) {
            this.editor = editor;
            return this;
        }

        public Builder setContext(Context context) {
            this.context = context;
            return this;
        }

        public ActionContext build() {
            return new ActionContext(context, compiler, editor, menu, task, currentFile, cursor,
                    diagnostic, currentPath);
        }
    }
}
