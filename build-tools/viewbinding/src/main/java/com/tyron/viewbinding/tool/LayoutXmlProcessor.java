/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.viewbinding.tool;

import com.tyron.viewbinding.tool.util.RelativizableFile;
import com.android.annotations.NonNull;
import org.apache.commons.io.FileUtils;
import org.xml.sax.SAXException;

import com.tyron.viewbinding.tool.store.LayoutFileParser;
import com.tyron.viewbinding.tool.store.ResourceBundle;
import com.tyron.viewbinding.tool.util.Preconditions;
import com.tyron.viewbinding.tool.writer.JavaFileWriter;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

/**
 * Processes the layout XML, stripping the binding attributes and elements
 * and writes the information into an annotated class file for the annotation
 * processor to work with.
 */
public class LayoutXmlProcessor {
    private static final FilenameFilter LAYOUT_FOLDER_FILTER = (dir, name)
            -> name.startsWith("layout");

    private static final FilenameFilter XML_FILE_FILTER = (dir, name)
            -> name.toLowerCase().endsWith(".xml");
    private final JavaFileWriter mFileWriter;
    private final ResourceBundle mResourceBundle;
    private boolean mProcessingComplete;
    private final OriginalFileLookup mOriginalFileLookup;

    public LayoutXmlProcessor(
            String applicationPackage,
            JavaFileWriter fileWriter,
            OriginalFileLookup originalFileLookup,
            boolean useAndroidX) {
        mFileWriter = fileWriter;
        mResourceBundle = new ResourceBundle(applicationPackage, useAndroidX);
        mOriginalFileLookup = originalFileLookup;
    }

    private static void processIncrementalInputFiles(ResourceInput input,
            ProcessFileCallback callback)
            throws IOException, ParserConfigurationException, XPathExpressionException,
            SAXException {
        processExistingIncrementalFiles(input.getRootInputFolder(), input.getAdded(), callback);
        processExistingIncrementalFiles(input.getRootInputFolder(), input.getChanged(), callback);
        processRemovedIncrementalFiles(input.getRootInputFolder(), input.getRemoved(), callback);
    }

    public static String exportLayoutNameFromInfoFileName(String infoFileName) {
        return infoFileName.substring(0, infoFileName.indexOf('-'));
    }

    private static void processExistingIncrementalFiles(File inputRoot, List<File> files,
            ProcessFileCallback callback)
            throws IOException, XPathExpressionException, SAXException,
            ParserConfigurationException {
        for (File file : files) {
            File parent = file.getParentFile();
            if (inputRoot.equals(parent)) {
                // configuration folders may show up in incremental lists. We should ignore them
                // since any file inside them also shows up
                if (!LAYOUT_FOLDER_FILTER.accept(file, file.getName())) {
                    callback.processOtherRootFile(file);
                }
            } else if (LAYOUT_FOLDER_FILTER.accept(parent, parent.getName())) {
                callback.processLayoutFile(file);
            } else {
                callback.processOtherFile(parent, file);
            }
        }
    }

    private static void processRemovedIncrementalFiles(File inputRoot, List<File> files,
            ProcessFileCallback callback)
            throws IOException {
        for (File file : files) {
            File parent = file.getParentFile();
            if (inputRoot.equals(parent)) {
                callback.processRemovedOtherRootFile(file);
            } else if (LAYOUT_FOLDER_FILTER.accept(parent, parent.getName())) {
                callback.processRemovedLayoutFile(file);
            } else {
                callback.processRemovedOtherFile(parent, file);
            }
        }
    }

    private static void processAllInputFiles(ResourceInput input, ProcessFileCallback callback)
            throws IOException, XPathExpressionException, SAXException,
            ParserConfigurationException {
        FileUtils.deleteDirectory(input.getRootOutputFolder());
        Preconditions.check(input.getRootOutputFolder().mkdirs(), "out dir should be re-created");
        Preconditions.check(input.getRootInputFolder().isDirectory(), "it must be a directory");
        //noinspection ConstantConditions
        for (File firstLevel : input.getRootInputFolder().listFiles()) {
            if (firstLevel.isDirectory()) {
                if (LAYOUT_FOLDER_FILTER.accept(firstLevel, firstLevel.getName())) {
                    callback.processLayoutFolder(firstLevel);
                    //noinspection ConstantConditions
                    for (File xmlFile : firstLevel.listFiles(XML_FILE_FILTER)) {
                        callback.processLayoutFile(xmlFile);
                    }
                } else {
                    callback.processOtherFolder(firstLevel);
                    //noinspection ConstantConditions
                    for (File file : firstLevel.listFiles()) {
                        callback.processOtherFile(firstLevel, file);
                    }
                }
            } else {
                callback.processOtherRootFile(firstLevel);
            }

        }
    }

    /**
     * used by the studio plugin
     */
    public ResourceBundle getResourceBundle() {
        return mResourceBundle;
    }

    public void processRemovedFile(File input) {
        mResourceBundle.addRemovedFile(input);
    }

    /** Processes a layout file which does not contain data binding constructs. */
    public void processFileWithNoDataBinding(@NonNull File file) {
        mResourceBundle.addFileWithNoDataBinding(file);
    }

    public boolean processSingleFile(@NonNull RelativizableFile input, @NonNull File output,
            boolean isViewBindingEnabled, boolean isDataBindingEnabled)
            throws ParserConfigurationException, SAXException, XPathExpressionException,
            IOException {
        final ResourceBundle.LayoutFileBundle bindingLayout = LayoutFileParser
                .parseXml(input, output, mResourceBundle.getAppPackage(), mOriginalFileLookup,
                        isViewBindingEnabled, isDataBindingEnabled);
        if (bindingLayout == null
                || (bindingLayout.isBindingData() && bindingLayout.isEmpty())) {
            return false;
        }
        mResourceBundle.addLayoutBundle(bindingLayout, true);
        return true;
    }

    public boolean processResources(
            ResourceInput input, boolean isViewBindingEnabled, boolean isDataBindingEnabled)
            throws ParserConfigurationException, SAXException, XPathExpressionException,
            IOException {
        if (mProcessingComplete) {
            return false;
        }
        final URI inputRootUri = input.getRootInputFolder().toURI();
        ProcessFileCallback callback = new ProcessFileCallback() {
            private File convertToOutFile(File file) {
                final String subPath = toSystemDependentPath(inputRootUri
                        .relativize(file.toURI()).getPath());
                return new File(input.getRootOutputFolder(), subPath);
            }
            @Override
            public void processLayoutFile(File file)
                    throws ParserConfigurationException, SAXException, XPathExpressionException,
                    IOException {
                processSingleFile(RelativizableFile.fromAbsoluteFile(file, null),
                        convertToOutFile(file), isViewBindingEnabled, isDataBindingEnabled);
            }

            @Override
            public void processOtherFile(File parentFolder, File file) throws IOException {
                final File outParent = convertToOutFile(parentFolder);
                FileUtils.copyFile(file, new File(outParent, file.getName()));
            }

            @Override
            public void processRemovedLayoutFile(File file) {
                mResourceBundle.addRemovedFile(file);
                final File out = convertToOutFile(file);
                FileUtils.deleteQuietly(out);
            }

            @Override
            public void processRemovedOtherFile(File parentFolder, File file) throws IOException {
                final File outParent = convertToOutFile(parentFolder);
                FileUtils.deleteQuietly(new File(outParent, file.getName()));
            }

            @Override
            public void processOtherFolder(File folder) {
                //noinspection ResultOfMethodCallIgnored
                convertToOutFile(folder).mkdirs();
            }

            @Override
            public void processLayoutFolder(File folder) {
                //noinspection ResultOfMethodCallIgnored
                convertToOutFile(folder).mkdirs();
            }

            @Override
            public void processOtherRootFile(File file) throws IOException {
                final File outFile = convertToOutFile(file);
                if (file.isDirectory()) {
                    FileUtils.copyDirectory(file, outFile);
                } else {
                    FileUtils.copyFile(file, outFile);
                }
            }

            @Override
            public void processRemovedOtherRootFile(File file) throws IOException {
                final File outFile = convertToOutFile(file);
                FileUtils.deleteQuietly(outFile);
            }
        };
        if (input.isIncremental()) {
            processIncrementalInputFiles(input, callback);
        } else {
            processAllInputFiles(input, callback);
        }
        mProcessingComplete = true;
        return true;
    }

    public static String toSystemDependentPath(String path) {
        if (File.separatorChar != '/') {
            path = path.replace('/', File.separatorChar);
        }
        return path;
    }

    public void writeLayoutInfoFiles(File xmlOutDir) throws JAXBException {
        writeLayoutInfoFiles(xmlOutDir, mFileWriter);
    }

    public JavaFileWriter getFileWriter() {
        return mFileWriter;
    }

    public void writeLayoutInfoFiles(File xmlOutDir, JavaFileWriter writer) throws JAXBException {
        // For each layout file, generate a corresponding layout info file
        for (ResourceBundle.LayoutFileBundle layout : mResourceBundle
                .getAllLayoutFileBundlesInSource()) {
            writeXmlFile(writer, xmlOutDir, layout);
        }

        // Delete stale layout info files due to removed/changed layout files. There are 2 cases:
        //   1. Layout files were removed
        //   2. Layout files previously containing data binding constructs are now no longer
        //      containing them (see bug 153711619). NOTE: This set of layout files is a subset of
        //      mResourceBundle.getFilesWithNoDataBinding() because
        //      mResourceBundle.getFilesWithNoDataBinding() may also contain layout files that do
        //      not have a history or did not have data binding constructs in the previous build, in
        //      which cases their associated layout info files don't exist.
        List<File> staleLayoutFiles = new ArrayList<>(mResourceBundle.getRemovedFiles());
        staleLayoutFiles.addAll(mResourceBundle.getFilesWithNoDataBinding());
        for (File staleLayoutFile : staleLayoutFiles) {
            File staleLayoutInfoFile = new File(xmlOutDir, generateExportFileName(staleLayoutFile));
            // Delete quietly as the file may not exist (see comment above)
            FileUtils.deleteQuietly(staleLayoutInfoFile);
        }
    }

    private void writeXmlFile(JavaFileWriter writer, File xmlOutDir,
            ResourceBundle.LayoutFileBundle layout)
            throws JAXBException {
        String filename = generateExportFileName(layout);
        writer.writeToFile(new File(xmlOutDir, filename), layout.toXML());
    }

    /**
     * Generates a string identifier that can uniquely identify the given layout bundle.
     * This identifier can be used when we need to export data about this layout bundle.
     */
    private static String generateExportFileName(ResourceBundle.LayoutFileBundle layout) {
        return generateExportFileName(layout.getFileName(), layout.getDirectory());
    }

    private static String generateExportFileName(File file) {
        final String fileName = file.getName();
        return generateExportFileName(fileName.substring(0, fileName.lastIndexOf('.')),
                file.getParentFile().getName());
    }

    public static String generateExportFileName(String fileName, String dirName) {
        return fileName + '-' + dirName + ".xml";
    }

    public String getPackage() {
        return mResourceBundle.getAppPackage();
    }

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

    /**
     * API agnostic class to get resource changes incrementally.
     */
    public static class ResourceInput {
        private final boolean mIncremental;
        private final File mRootInputFolder;
        private final File mRootOutputFolder;

        private List<File> mAdded = new ArrayList<>();
        private List<File> mRemoved = new ArrayList<>();
        private List<File> mChanged = new ArrayList<>();

        public ResourceInput(boolean incremental, File rootInputFolder, File rootOutputFolder) {
            mIncremental = incremental;
            mRootInputFolder = rootInputFolder;
            mRootOutputFolder = rootOutputFolder;
        }

        public void added(File file) {
            mAdded.add(file);
        }
        public void removed(File file) {
            mRemoved.add(file);
        }
        public void changed(File file) {
            mChanged.add(file);
        }

        public boolean shouldCopy() {
            return !mRootInputFolder.equals(mRootOutputFolder);
        }

        List<File> getAdded() {
            return mAdded;
        }

        List<File> getRemoved() {
            return mRemoved;
        }

        List<File> getChanged() {
            return mChanged;
        }

        File getRootInputFolder() {
            return mRootInputFolder;
        }

        File getRootOutputFolder() {
            return mRootOutputFolder;
        }

        public boolean isIncremental() {
            return mIncremental;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            out.append("ResourceInput{")
                    .append("mIncremental=").append(mIncremental)
                    .append(", mRootInputFolder=").append(mRootInputFolder)
                    .append(", mRootOutputFolder=").append(mRootOutputFolder);
            logFiles(out, "added", mAdded);
            logFiles(out, "removed", mRemoved);
            logFiles(out, "changed", mChanged);
            return out.toString();

        }

        private static void logFiles(StringBuilder out, String name, List<File> files) {
            out.append("\n  ").append(name);
            for (File file : files) {
                out.append("\n   - ").append(file.getAbsolutePath());
            }
        }
    }

    private interface ProcessFileCallback {
        void processLayoutFile(File file)
                throws ParserConfigurationException, SAXException, XPathExpressionException,
                IOException;
        void processOtherFile(File parentFolder, File file) throws IOException;
        void processRemovedLayoutFile(File file);
        void processRemovedOtherFile(File parentFolder, File file) throws IOException;

        void processOtherFolder(File folder);

        void processLayoutFolder(File folder);

        void processOtherRootFile(File file) throws IOException;

        void processRemovedOtherRootFile(File file) throws IOException;
    }
}
