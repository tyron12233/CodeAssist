package com.tyron.builder.compiler.java;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.util.Decompress;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Task responsible for copying aars/jars from libraries to build/libs
 */
public class CheckLibrariesTask extends Task<JavaModule> {

    public CheckLibrariesTask(Project project, JavaModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return CheckLibrariesTask.class.getSimpleName();
    }

    @Override
    public void prepare(BuildType type) throws IOException {

    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        checkLibraries(getModule(), getLogger(), Collections.emptyList());
    }

    private void checkLibraries(JavaModule project, ILogger logger, List<File> newLibraries) throws IOException {
        Set<Library> libraries = new HashSet<>();

        Map<String, Library> fileLibsHashes = new HashMap<>();
        File[] fileLibraries = project.getLibraryDirectory().listFiles(c ->
                c.getName().endsWith(".aar") || c.getName().endsWith(".jar"));
        if (fileLibraries != null) {
            for (File fileLibrary : fileLibraries) {
                try {
                    ZipFile zipFile = new ZipFile(fileLibrary);
                    Library library = new Library();
                    library.setSourceFile(fileLibrary);
                    fileLibsHashes.put(calculateMD5(fileLibrary), library);
                } catch (IOException e) {
                    String message = "File " + fileLibrary +
                            " is corrupt! Ignoring.";
                    logger.warning(message);
                }
            }
        }


        newLibraries.forEach(it -> {
            Library library = new Library();
            library.setSourceFile(it);
            libraries.add(library);
        });

        String librariesString = project.getSettings().getString("libraries", "[]");
        try {
            List<Library> parsedLibraries = new Gson().fromJson(librariesString, new TypeToken<List<Library>>() {}.getType());
            if (parsedLibraries != null) {
                libraries.addAll(parsedLibraries);
            }
        } catch (Exception ignore) {

        }

        Map<String, Library> md5Map = new HashMap<>();
        libraries.forEach(it ->
                md5Map.put(calculateMD5(it.getSourceFile()), it));
        File buildLibs = new File(project.getBuildDirectory(), "libs");
        File[] buildLibraryDirs = buildLibs.listFiles(File::isDirectory);
        if (buildLibraryDirs != null) {
            for (File libraryDir : buildLibraryDirs) {
                String md5Hash = libraryDir.getName();
                if (!md5Map.containsKey(md5Hash) && !fileLibsHashes.containsKey(md5Hash)) {
                    FileUtils.deleteDirectory(libraryDir);
                    Log.d("LibraryCheck", "Deleting contents of " + md5Hash);
                }
            }
        }

        saveLibraryToProject(project, md5Map, fileLibsHashes);
    }

    private void saveLibraryToProject(Module module, Map<String, Library> libraries, Map<String, Library> fileLibraries) throws IOException {
        Map<String, Library> combined = new HashMap<>();
        combined.putAll(libraries);
        combined.putAll(fileLibraries);

        getModule().putLibraryHashes(combined);

        for (Map.Entry<String, Library> entry : combined.entrySet()) {
            String hash = entry.getKey();
            Library library = entry.getValue();

            File libraryDir = new File(module.getBuildDirectory(), "libs/" + hash);
            if (!libraryDir.exists()) {
                libraryDir.mkdir();
            } else {
                continue;
            }

            if (library.getSourceFile().getName().endsWith(".jar")) {
                FileUtils.copyFileToDirectory(library.getSourceFile(), libraryDir);

                File file = new File(libraryDir, library.getSourceFile().getName());
                file.renameTo(new File(libraryDir, "classes.jar"));
            } else if (library.getSourceFile().getName().endsWith(".aar")) {
                Decompress.unzip(library.getSourceFile().getAbsolutePath(),
                        libraryDir.getAbsolutePath());
            }
        }

        String librariesString = new Gson().toJson(libraries.values());
        module.getSettings().edit()
                .putString("libraries", librariesString)
                .apply();
    }

    public static String calculateMD5(File updateFile) {
        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e("calculateMD5", "Exception while getting FileInputStream", e);
            return null;
        }

        return calculateMD5(is);
    }

    public static String calculateMD5(InputStream is) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e("calculateMD5", "Exception while getting Digest", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e("calculateMD5", "Exception on closing MD5 input stream", e);
            }
        }
    }
}
