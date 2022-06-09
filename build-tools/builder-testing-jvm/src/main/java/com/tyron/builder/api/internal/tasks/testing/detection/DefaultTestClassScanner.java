/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.internal.tasks.testing.detection;

import com.tyron.builder.api.file.EmptyFileVisitor;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.ReproducibleFileVisitor;
import com.tyron.builder.api.internal.file.RelativeFile;
import com.tyron.builder.api.internal.tasks.testing.DefaultTestClassRunInfo;
import com.tyron.builder.api.internal.tasks.testing.TestClassProcessor;
import com.tyron.builder.api.internal.tasks.testing.TestClassRunInfo;

import java.util.regex.Pattern;

/**
 * The default test class scanner. Depending on the availability of a test framework detector,
 * a detection or filename scan is performed to find test classes.
 */
public class DefaultTestClassScanner implements Runnable {
    private static final Pattern ANONYMOUS_CLASS_NAME = Pattern.compile(".*\\$\\d+");
    private final FileTree candidateClassFiles;
    private final TestFrameworkDetector testFrameworkDetector;
    private final TestClassProcessor testClassProcessor;

    public DefaultTestClassScanner(FileTree candidateClassFiles, TestFrameworkDetector testFrameworkDetector,
                                   TestClassProcessor testClassProcessor) {
        this.candidateClassFiles = candidateClassFiles;
        this.testFrameworkDetector = testFrameworkDetector;
        this.testClassProcessor = testClassProcessor;
    }

    @Override
    public void run() {
        if (testFrameworkDetector == null) {
            filenameScan();
        } else {
            detectionScan();
        }
    }

    private void detectionScan() {
        testFrameworkDetector.startDetection(testClassProcessor);
        candidateClassFiles.visit(new ClassFileVisitor() {
            @Override
            public void visitClassFile(FileVisitDetails fileDetails) {
                testFrameworkDetector.processTestClass(new RelativeFile(fileDetails.getFile(), fileDetails.getRelativePath()));
            }
        });
    }

    private void filenameScan() {
        candidateClassFiles.visit(new ClassFileVisitor() {
            @Override
            public void visitClassFile(FileVisitDetails fileDetails) {
                TestClassRunInfo testClass = new DefaultTestClassRunInfo(getClassName(fileDetails));
                testClassProcessor.processTestClass(testClass);
            }
        });
    }

    private abstract class ClassFileVisitor extends EmptyFileVisitor implements ReproducibleFileVisitor {
        @Override
        public void visitFile(FileVisitDetails fileDetails) {
            if (isClass(fileDetails) && !isAnonymousClass(fileDetails)) {
                visitClassFile(fileDetails);
            }
        }

        abstract void visitClassFile(FileVisitDetails fileDetails);

        private boolean isAnonymousClass(FileVisitDetails fileVisitDetails) {
            return ANONYMOUS_CLASS_NAME.matcher(getClassName(fileVisitDetails)).matches();
        }

        private boolean isClass(FileVisitDetails fileVisitDetails) {
            String fileName = fileVisitDetails.getFile().getName();
            return fileName.endsWith(".class") && !"module-info.class".equals(fileName);
        }

        @Override
        public boolean isReproducibleFileOrder() {
            return true;
        }
    }

    private String getClassName(FileVisitDetails fileDetails) {
        return fileDetails.getRelativePath().getPathString().replaceAll("\\.class", "").replace('/', '.');
    }
}
