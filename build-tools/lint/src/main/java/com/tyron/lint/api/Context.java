package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.JavaModule;
import com.tyron.lint.client.Configuration;
import com.tyron.lint.client.LintDriver;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class Context {

    public final File file;

    protected final LintDriver mDriver;

    private final JavaModule mProject;

    private final Configuration mConfiguration;

    private String contents;

    private Map<String, Object> mProperties;

    private Boolean mContainsCommentSuppress;

    public Context(LintDriver driver, JavaModule project, File file, Configuration config) {
        this.file = file;

        mDriver = driver;
        mProject = project;
        mConfiguration = config;
    }

    public String getContents() {
        if (contents == null) {
            try {
                contents = FileUtils.readFileToString(file, Charset.defaultCharset());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return contents;
    }


    /** Returns the comment marker used in Studio to suppress statements for language, if any */
    @Nullable
    protected String getSuppressCommentPrefix() {
        // Java and XML files are handled in sub classes (XmlContext, JavaContext)

        String path = file.getPath();
        if (path.endsWith(".java>") || path.endsWith(".gradle")) {
            return JavaContext.SUPPRESS_COMMENT_PREFIX;
        } else if (path.endsWith(".cfg") || path.endsWith(".pro")) {
            return "#suppress ";
        }

        return null;
    }
    public boolean containsCommentSuppress() {
        if (mContainsCommentSuppress == null) {
            mContainsCommentSuppress = false;
            String prefix = getSuppressCommentPrefix();
            if (prefix != null) {
                String contents = getContents();
                if (contents != null) {
                    mContainsCommentSuppress = contents.contains(prefix);
                }
            }
        }

        return mContainsCommentSuppress;
    }

    public void report(
            @NonNull Issue issue,
            @Nullable Location location,
            @NonNull String message) {
        Severity severity = mConfiguration.getSeverity(issue);
        if  (severity == Severity.IGNORE) {
            return;
        }

        mDriver.getClient().report(this, issue, severity, location, message, TextFormat.RAW);
    }
    public void setProperty(@NonNull String name, @Nullable Object value) {
        if (value == null) {
            if (mProperties != null) {
                mProperties.remove(name);
            }
        } else {
            if (mProperties == null) {
                mProperties = new HashMap<String, Object>();
            }
            mProperties.put(name, value);
        }
    }
}
