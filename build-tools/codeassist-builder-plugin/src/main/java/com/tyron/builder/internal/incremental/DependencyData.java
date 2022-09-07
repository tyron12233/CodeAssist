package com.tyron.builder.internal.incremental;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

/**
 * Holds dependency information, including the main compiled file, secondary input files
 * (usually headers), and output files.
 */
public class DependencyData {

    @NonNull
    private String mMainFile;
    @NonNull
    private List<String> mSecondaryFiles = Lists.newArrayList();
    @NonNull
    private List<String> mOutputFiles = Lists.newArrayList();
    @NonNull List<String> mSecondaryOutputFiles = Lists.newArrayList();

    DependencyData() {
    }

    @NonNull
    public String getMainFile() {
        return mMainFile;
    }

    void setMainFile(@NonNull String path) {
        mMainFile = path;
    }

    @NonNull
    public List<String> getSecondaryFiles() {
        return mSecondaryFiles;
    }

    void addSecondaryFile(@NonNull String path) {
        mSecondaryFiles.add(path);
    }

    @NonNull
    public List<String> getOutputFiles() {
        return mOutputFiles;
    }

    void addOutputFile(@NonNull String path) {
        mOutputFiles.add(path);
    }

    public void addSecondaryOutputFile(@NonNull String path) {
        mSecondaryOutputFiles.add(path);
    }

    @NonNull
    public List<String> getSecondaryOutputFiles() {
        return mSecondaryOutputFiles;
    }

    /**
     * Parses the given dependency file and returns the parsed data
     *
     * @param dependencyFile the dependency file
     */
    @Nullable
    public static DependencyData parseDependencyFile(@NonNull File dependencyFile)
            throws IOException {
        // first check if the dependency file is here.
        if (!dependencyFile.isFile()) {
            return null;
        }

        try (Stream<String> lines = Files.lines(dependencyFile.toPath(), Charsets.UTF_8)) {
            return processDependencyData(lines::iterator);
        }
    }

    private enum ParseMode {
        OUTPUT, MAIN, SECONDARY, DONE
    }

    @VisibleForTesting
    @Nullable
    static DependencyData processDependencyData(@NonNull Iterable<String> content) {
        // The format is technically:
        // output1 output2 [...]: dep1 dep2 [...]
        // However, the current tools generating those files guarantee that each file path
        // is on its own line, making it simpler to handle windows paths as well as path
        // with spaces in them.

        DependencyData data = new DependencyData();

        ParseMode parseMode = ParseMode.OUTPUT;

        for (String line : content) {
            line = line.trim();

            // check for separator at the beginning
            if (line.startsWith(":")) {
                parseMode = ParseMode.MAIN;
                line = line.substring(1).trim();
            }

            ParseMode nextMode = parseMode;

            // remove the \ at the end.
            if (line.endsWith("\\")) {
                line = line.substring(0, line.length() - 1).trim();
            }

            // detect : at the end indicating a parse mode change *after* we process this line.
            if (line.endsWith(":")) {
                if (parseMode == ParseMode.SECONDARY) {
                    nextMode = ParseMode.DONE;
                } else {
                    nextMode = ParseMode.MAIN;
                }
                line = line.substring(0, line.length() - 1).trim();
            }

            if (nextMode == ParseMode.DONE) {
                break;
            }

            if (!line.isEmpty()) {
                switch (parseMode) {
                    case OUTPUT:
                        data.addOutputFile(line);
                        break;
                    case MAIN:
                        data.setMainFile(line);
                        nextMode = ParseMode.SECONDARY;
                        break;
                    case SECONDARY:
                        data.addSecondaryFile(line);
                        break;
                }
            }

            parseMode = nextMode;
        }

        if (data.getMainFile() == null) {
            return null;
        }

        return data;
    }

    @Override
    public String toString() {
        return "DependencyData{" +
                "mMainFile='" + mMainFile + '\'' +
                ", mSecondaryFiles=" + mSecondaryFiles +
                ", mOutputFiles=" + mOutputFiles +
                '}';
    }
}