package com.tyron.builder.model;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;

import java.io.File;
import java.util.Locale;

public class DiagnosticWrapper implements Diagnostic<File> {
    
    private String code;
    private File source;
    private Kind kind;
    
    private long position;
    private long startPosition;
    private long endPosition;

    private long lineNumber;
    private long columnNumber;
    
    private String message;
    
    public DiagnosticWrapper() {

    }

    public DiagnosticWrapper(Diagnostic<? extends JavaFileObject> obj) {
        this.code = obj.getCode();
        this.source = new File(obj.getSource().toUri());
        this.kind = obj.getKind();

        this.position = obj.getPosition();
        this.startPosition = obj.getStartPosition();
        this.endPosition = obj.getEndPosition();

        this.lineNumber = obj.getLineNumber();
        this.columnNumber = obj.getColumnNumber();

        this.message = obj.getMessage(Locale.getDefault());
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
}
