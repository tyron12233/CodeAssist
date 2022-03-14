package com.tyron.viewbinding.tool;

import java.io.File;

/*
 * Partially adopted from android.databinding.tool.LayoutXmlProcessor
 */
public class LayoutXmlProcessor {
    /**
     * Helper interface that can find the original copy of a resource XML.
     */
    public interface OriginalFileLookup {

        /**
         * @param file The intermediate build file
         * @return The original file or null if original File cannot be found.
         */
        File getOriginalFileFor(File file);
    }
}
