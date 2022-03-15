package com.tyron.builder.model;

import android.view.View;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

public class DiagnosticWrapper implements Diagnostic<File> {

    public static final int USE_LINE_POS = -31;

    private String code;
    private File source;
    private Kind kind;
    
    private long position;
    private long startPosition;
    private long endPosition;

    private long lineNumber;
    private long columnNumber;
    private View.OnClickListener onClickListener;
    private String message;

    /** Extra information for this diagnostic */
    private Object mExtra;
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;

    public DiagnosticWrapper() {

    }

    public DiagnosticWrapper(Diagnostic<? extends JavaFileObject> obj) {
        try {
            this.code = obj.getCode();
            if (obj.getSource() != null) {
                this.source = new File(obj.getSource().toUri());
            }
            this.kind = obj.getKind();

            this.position = obj.getPosition();
            this.startPosition = obj.getStartPosition();
            this.endPosition = obj.getEndPosition();

            this.lineNumber = obj.getLineNumber();
            this.columnNumber = obj.getColumnNumber();

            this.message = obj.getMessage(Locale.getDefault());

            this.mExtra = obj;
        } catch (Throwable e) {
            // ignored
        }
    }

    public void setOnClickListener(View.OnClickListener listener) {
        onClickListener = listener;
    }

    public View.OnClickListener getOnClickListener() {
        return onClickListener;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public File getSource() {
        return source;
    }

    @Override
    public long getPosition() {
        return position;
    }

    @Override
    public long getStartPosition() {
        return startPosition;
    }

    @Override
    public long getEndPosition() {
        return endPosition;
    }

    @Override
    public long getLineNumber() {
        return lineNumber;
    }

    @Override
    public long getColumnNumber() {
        return columnNumber;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage(Locale locale) {
        return message;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setSource(File source) {
        this.source = source;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public void setStartPosition(long startPosition) {
        this.startPosition = startPosition;
    }

    public void setEndPosition(long endPosition) {
        this.endPosition = endPosition;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setLineNumber(long lineNumber) {
        this.lineNumber = lineNumber;
    }

    public void setColumnNumber(long columnNumber) {
        this.columnNumber = columnNumber;
    }

    public Object getExtra() {
        return mExtra;
    }

    public void setExtra(Object mExtra) {
        this.mExtra = mExtra;
    }

    @Override
    public String toString() {
        return "startOffset: " + startPosition + "\n" +
                "endOffset: " + endPosition + "\n" +
                "position: " + position + "\n" +
                "startLine: " + startLine + "\n" +
                "startColumn: " + startColumn + "\n" +
                "endLine: " + endLine + "\n" +
                "endColumn: " + endColumn + "\n" +
                "message: " + message;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, source, kind, position, startPosition, endPosition, lineNumber,
                columnNumber, message, mExtra);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DiagnosticWrapper) {
            DiagnosticWrapper that = (DiagnosticWrapper) obj;

            if (that.message != null && this.message == null) {
                return false;
            }

            if (that.message == null && this.message != null) {
                return false;
            }

            if (!Objects.equals(that.message, this.message)) {
                return false;
            }

            if (!Objects.equals(that.source, this.source)) {
                return false;
            }

            if (that.lineNumber != this.lineNumber) {
                return false;
            }

            return that.columnNumber == this.columnNumber;
        }
        return super.equals(obj);
    }

    public void setStartLine(int line) {
        this.startLine = line;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = startColumn;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }
}
