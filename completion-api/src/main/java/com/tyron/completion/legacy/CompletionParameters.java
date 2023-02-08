package com.tyron.completion.legacy;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.model.CompletionList;

import java.io.File;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Contains useful information about the current completion request
 */
public class CompletionParameters {

    private final Project mProject;
    private final Module mModule;
    private final File mFile;
    private final String mContents;
    private final String mPrefix;
    private final int mLine;
    private final int mColumn;
    private final long mIndex;
    private CompletionList.Builder builder;
    private final CodeEditor mEditor;

    public static Builder builder() {
        return new Builder();
    }

    private CompletionParameters(Project project,
                                 Module module,
                                 CodeEditor editor,
                                 File file,
                                 String contents,
                                 String prefix,
                                 int line,
                                 int column,
                                 long index, CompletionList.Builder builder) {
        mProject = project;
        mModule = module;
        mEditor = editor;
        mFile = file;
        mContents = contents;
        mPrefix = prefix;
        mLine = line;
        mColumn = column;
        mIndex = index;
        this.builder = builder;
    }

    public Project getProject() {
        return mProject;
    }

    public Module getModule() {
        return mModule;
    }

    public File getFile() {
        return mFile;
    }

    public CompletionList.Builder getBuilder() {
        return builder;
    }

    public String getContents() {
        return mContents;
    }

    public String getPrefix() {
        return mPrefix;
    }

    public int getLine() {
        return mLine;
    }

    public int getColumn() {
        return mColumn;
    }

    public long getIndex() {
        return mIndex;
    }

    public CodeEditor getEditor() {
        return mEditor;
    }

    @Override
    public String toString() {
        return "CompletionParameters{" +
               "mProject=" +
               mProject +
               ", mModule=" +
               mModule +
               ", mFile=" +
               mFile +
               '\'' +
               ", mPrefix='" +
               mPrefix +
               '\'' +
               ", mLine=" +
               mLine +
               ", mColumn=" +
               mColumn +
               ", mIndex=" +
               mIndex +
               ", mEditor=" +
               mEditor +
               '}';
    }

    public static final class Builder {

        private Project project;
        private Module module;
        private File file;
        private String contents;
        private String prefix;
        private int line;
        private int column;
        private long index;
        private CodeEditor editor;
        private CompletionList.Builder builder;

        private Builder() {

        }

        public Builder setProject(Project project) {
            this.project = project;
            return this;
        }

        public Builder setModule(Module module) {
            this.module = module;
            return this;
        }

        public Builder setFile(File file) {
            this.file = file;
            return this;
        }

        public Builder setContents(String contents) {
            this.contents = contents;
            return this;
        }

        public Builder setPrefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder setLine(int line) {
            this.line = line;
            return this;
        }

        public Builder setColumn(int column) {
            this.column = column;
            return this;
        }

        public Builder setIndex(long index) {
            this.index = index;
            return this;
        }

        public Builder setEditor(CodeEditor editor) {
            this.editor = editor;
            return this;
        }

        public CompletionParameters build() {
            return new CompletionParameters(project, module, editor, file, contents, prefix, line,
                                            column, index, builder);
        }

        public Builder setCompletionListBuilder(CompletionList.Builder builder) {
            this.builder = builder;
            return this;
        }
    }
}
