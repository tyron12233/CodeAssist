package com.tyron.builder.project.indexer;

import com.tyron.builder.project.CommonProjectKeys;
import com.tyron.builder.project.Project;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JavaFilesIndexer implements Indexer {
    @Override
    public List<File> index(Project project) {
        File userData = project.getUserData(CommonProjectKeys.JAVA_DIR_KEY);
        if (userData == null) {
            // should never happen
            return Collections.emptyList();
        }

        try {
            List<File> files = new ArrayList<>();
            Files.walkFileTree(userData.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toFile().getName().endsWith(".java")) {
                        files.add(file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return files;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
