package com.tyron.builder.util.internal;

import org.apache.commons.io.IOUtils;
import com.tyron.builder.internal.IoActions;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarUtil {
    public static boolean extractZipEntry(File jarFile, String entryName, File extractToFile) throws IOException {
        boolean entryExtracted = false;

        ZipInputStream zipStream = null;
        BufferedOutputStream extractTargetStream = null;
        try {
            zipStream = new ZipInputStream(new FileInputStream(jarFile));
            extractTargetStream = new BufferedOutputStream(new FileOutputStream(extractToFile));

            boolean classFileExtracted = false;
            boolean zipStreamEndReached = false;
            while (!classFileExtracted && !zipStreamEndReached) {
                final ZipEntry candidateZipEntry = zipStream.getNextEntry();

                if (candidateZipEntry == null) {
                    zipStreamEndReached = true;
                } else {
                    if (candidateZipEntry.getName().equals(entryName)) {
                        IOUtils.copy(zipStream, extractTargetStream);
                        classFileExtracted = true;
                        entryExtracted = true;
                    }
                }
            }
        } finally {
            IoActions.closeQuietly(zipStream);
            IoActions.closeQuietly(extractTargetStream);
        }

        return entryExtracted;
    }
}
