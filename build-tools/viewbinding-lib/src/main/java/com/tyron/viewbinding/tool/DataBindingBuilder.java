package com.tyron.viewbinding.tool;

import com.tyron.viewbinding.tool.util.L;
import com.tyron.viewbinding.tool.writer.JavaFileWriter;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/*
 * Partially adopted from android.databinding.tool.DataBindingBuilder
 */
public class DataBindingBuilder {

    public static class GradleFileWriter extends JavaFileWriter {

        private final String outputBase;

        public GradleFileWriter(String outputBase) {
            this.outputBase = outputBase;
        }

        @Override
        public void writeToFile(String canonicalName, String contents) {
            File f = toFile(canonicalName);
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                IOUtils.write(contents, fos);
            } catch (IOException e) {
                L.e(e, "cannot write file " + f.getAbsolutePath());
            } finally {
                IOUtils.closeQuietly(fos);
            }
        }

        private File toFile(String canonicalName) {
            String asPath = canonicalName.replace('.', File.separatorChar);
            return new File(outputBase + File.separatorChar + asPath + ".java");
        }

        @Override
        public void deleteFile(String canonicalName) {
            FileUtils.deleteQuietly(toFile(canonicalName));
        }
    }
}
