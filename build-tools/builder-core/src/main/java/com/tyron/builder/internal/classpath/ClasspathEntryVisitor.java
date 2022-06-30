package com.tyron.builder.internal.classpath;

import com.tyron.builder.api.file.RelativePath;

import java.io.IOException;

public interface ClasspathEntryVisitor {
    /**
     * Visits the contents of a classpath element.
     */
    void visit(Entry entry) throws IOException;

    interface Entry {
        String getName();

        RelativePath getPath();

        /**
         * Can be called at most once for a given entry. If not called, content is skipped.
         */
        byte[] getContent() throws IOException;
    }
}
