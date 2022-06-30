/*
 * Copyright 2019 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.transform;

import com.google.common.io.Files;
import org.apache.commons.io.IOUtils;
import com.tyron.builder.api.artifacts.transform.InputArtifact;
import com.tyron.builder.api.artifacts.transform.TransformAction;
import com.tyron.builder.api.artifacts.transform.TransformOutputs;
import com.tyron.builder.api.artifacts.transform.TransformParameters;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.work.DisableCachingByDefault;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.apache.commons.io.FilenameUtils.removeExtension;

/**
 * Provides a generic transform from a zipped file to an extracted directory.  The extracted directory
 * is located in the output directory of the transform and is named after the zipped file name
 * minus the extension.
 */
@DisableCachingByDefault(because = "Not worth caching")
public abstract class UnzipTransform implements TransformAction<TransformParameters.None> {

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    public abstract Provider<FileSystemLocation> getZippedFile();

    @Override
    public void transform(TransformOutputs outputs) {
        File zippedFile = getZippedFile().get().getAsFile();
        String unzippedDirName = removeExtension(zippedFile.getName());
        File unzipDir = outputs.dir(unzippedDirName);
        try {
            unzipTo(zippedFile, unzipDir);
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private static void unzipTo(File headersZip, File unzipDir) throws IOException {
        try (ZipInputStream inputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(headersZip)))) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                File outFile = new File(unzipDir, entry.getName());
                Files.createParentDirs(outFile);
                try (FileOutputStream outputStream = new FileOutputStream(outFile)) {
                    IOUtils.copyLarge(inputStream, outputStream);
                }
            }
        }
    }
}
