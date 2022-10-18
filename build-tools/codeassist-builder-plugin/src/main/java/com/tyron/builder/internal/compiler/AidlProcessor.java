package com.tyron.builder.internal.compiler;

import static com.tyron.common.TestUtil.isWindows;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessExecutor;
import com.android.ide.common.process.ProcessInfoBuilder;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.ide.common.process.ProcessResult;
import com.android.utils.FileUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.tyron.builder.compiling.DependencyFileProcessor;
import com.tyron.builder.internal.incremental.DependencyData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A Source File processor for AIDL files. This compiles each aidl file found by the SourceSearcher.
 */
public class AidlProcessor {
    public static void call(
            @NonNull String aidlExecutable,
            @NonNull String frameworkLocation,
            @NonNull Iterable<File> importFolders,
            @NonNull File sourceOutputDir,
            @Nullable File packagedOutputDir,
            @Nullable Collection<String> packagedList,
            @NonNull DependencyFileProcessor dependencyFileProcessor,
            @NonNull ProcessExecutor processExecutor,
            @NonNull ProcessOutputHandler processOutputHandler,
            @NonNull Path startDir,
            @NonNull Path inputFilePath)
            throws IOException {

        @NonNull final Collection<String> nonNullPackagedList;
        if (packagedList == null) {
            nonNullPackagedList = ImmutableSet.of();
        } else {
            nonNullPackagedList = Collections.unmodifiableSet(Sets.newHashSet(packagedList));
        }

        ProcessInfoBuilder builder = new ProcessInfoBuilder();

        builder.setExecutable(aidlExecutable);

        builder.addArgs("-p" + frameworkLocation);
        builder.addArgs("-o" + sourceOutputDir.getAbsolutePath());

        // add all the library aidl folders to access parcelables that are in libraries
        for (File f : importFolders) {
            builder.addArgs("-I" + f.getAbsolutePath());
        }

        // create a temp file for the dependency
        File depFile = File.createTempFile("aidl", ".d");
        builder.addArgs("-d" + depFile.getAbsolutePath());

        builder.addArgs(inputFilePath.toAbsolutePath().toString());

        ProcessResult result =
                processExecutor.execute(builder.createProcess(), processOutputHandler);

        try {
            result.rethrowFailure().assertNormalExitValue();
        } catch (ProcessException pe) {
            throw new IOException(pe);
        }

        String relativeInputFile =
                FileUtils.toSystemIndependentPath(
                        makeRelative(startDir.toFile(), inputFilePath.toFile()));

        // send the dependency file to the processor.
        DependencyData data = dependencyFileProcessor.processFile(depFile);

        if (data != null) {
            // As of build tools 29.0.2, Aidl no longer produces an empty list of output files
            // so we need to check each file in it for content and delete the empty java files
            boolean isParcelable = true;

            List<String> outputFiles = data.getOutputFiles();

            if (!outputFiles.isEmpty()) {
                for (String path : outputFiles) {
                    List<String> outputFileContent =
                            Files.readLines(new File(path), StandardCharsets.UTF_8);
                    String emptyFileLine =
                            "// This file is intentionally left blank as placeholder for parcel declaration.";
                    if (outputFileContent.size() <= 2
                            && outputFileContent.get(0).equals(emptyFileLine)) {
                        FileUtils.delete(new File(path));
                    } else {
                        isParcelable = false;
                    }
                }
            }

            boolean isPackaged = nonNullPackagedList.contains(relativeInputFile);

            if (packagedOutputDir != null && (isParcelable || isPackaged)) {
                // looks like a parcelable or is listed for packaging
                // Store it in the secondary output of the DependencyData object.

                File destFile = new File(packagedOutputDir, relativeInputFile);
                //noinspection ResultOfMethodCallIgnored
                destFile.getParentFile().mkdirs();
                Files.copy(inputFilePath.toFile(), destFile);
                data.addSecondaryOutputFile(destFile.getPath());
            }
        }

        FileUtils.delete(depFile);
    }

    /**
     * Computes a relative path from "toBeRelative" relative to "baseDir".
     *
     * <p>Rule: - let relative2 = makeRelative(path1, path2) - then pathJoin(path1 + relative2) ==
     * path2 after canonicalization.
     *
     * <p>Principle: - let base = /c1/c2.../cN/a1/a2../aN - let toBeRelative =
     * /c1/c2.../cN/b1/b2../bN - result is removes the common paths, goes back from aN to cN then to
     * bN: - result = ../..../../1/b2../bN
     *
     * @param baseDir The base directory to be relative to.
     * @param toBeRelative The file or directory to make relative to the base.
     * @return A path that makes toBeRelative relative to baseDir.
     * @throws IOException If drive letters don't match on Windows or path canonicalization fails.
     */
    @NonNull
    public static String makeRelative(@NonNull File baseDir, @NonNull File toBeRelative)
            throws IOException {
        return makeRelativeImpl(
                baseDir.getCanonicalPath(),
                toBeRelative.getCanonicalPath(),
                File.separator);
    }

    /**
     * Implementation detail of makeRelative to make it testable Independently of the platform.
     */
    @VisibleForTesting
    @NonNull
    static String makeRelativeImpl(@NonNull String path1,
                                   @NonNull String path2,
                                   @NonNull String dirSeparator)
            throws IOException {
        if (isWindows()) {
            // Check whether both path are on the same drive letter, if any.
            char drive1 = (path1.length() >= 2 && path1.charAt(1) == ':') ? path1.charAt(0) : 0;
            char drive2 = (path2.length() >= 2 && path2.charAt(1) == ':') ? path2.charAt(0) : 0;
            if (drive1 != drive2) {
                // Either a mix of UNC vs drive or not the same drives.
                throw new IOException("makeRelative: incompatible drive letters");
            }
        }

        String[] segments1 = path1.split(Pattern.quote(dirSeparator));
        String[] segments2 = path2.split(Pattern.quote(dirSeparator));

        int len1 = segments1.length;
        int len2 = segments2.length;
        int len = Math.min(len1, len2);
        int start = 0;
        for (; start < len; start++) {
            // On Windows should compare in case-insensitive.
            // Mac and Linux file systems can be both type, although their default
            // is generally to have a case-sensitive file system.
            if ((isWindows() && !segments1[start].equalsIgnoreCase(segments2[start]))
                || (!isWindows() && !segments1[start].equals(segments2[start]))) {
                break;
            }
        }

        StringBuilder result = new StringBuilder();
        for (int i = start; i < len1; i++) {
            result.append("..").append(dirSeparator);
        }
        while (start < len2) {
            result.append(segments2[start]);
            if (++start < len2) {
                result.append(dirSeparator);
            }
        }

        return result.toString();
    }
}
