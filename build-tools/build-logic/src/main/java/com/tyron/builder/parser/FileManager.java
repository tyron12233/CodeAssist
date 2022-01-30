package com.tyron.builder.parser;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.tyron.builder.BuildModule;
import com.tyron.builder.model.Project;
import com.tyron.common.util.Cache;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class responsible for caching java files for fast
 * lookup later whenever we need to.
 */
public class FileManager {

    private static class OpenedFile {

        private final String text;
        private final File file;

        private OpenedFile(String text, File file) {
            this.text = text;
            this.file = file;
        }
    }

    private static FileManager INSTANCE = null;
    private final ExecutorService service = Executors.newFixedThreadPool(4);

    private Project mCurrentProject;
    /**
     * Cache of java class files, keys can contain Dex files, Java class files and the values are
     * the files corresponding to it
     */
    private Cache<String, List<File>> classCache = new Cache<>();
    private Cache<String, List<File>> mDexCache = new Cache<>();
    private Cache<Void, Void> mSymbolCache = new Cache<>();

    // Map of compiled (.class) files with their fully qualified name as key
    private final Map<String, File> classFiles = new HashMap<>();

    private final Map<File, OpenedFile> mOpenedFiles = new HashMap<>();

    public static FileManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FileManager();
        }
        return INSTANCE;
    }

    @VisibleForTesting
    public static FileManager getInstance(File androidJar, File lambdaStubs) {
        if (INSTANCE == null) {
            INSTANCE = new FileManager(androidJar, lambdaStubs);
        }
        return INSTANCE;
    }

    @VisibleForTesting
    public FileManager(File androidJar, File lambdaStubs) {
        try {
            putJar(androidJar);
        } catch (IOException ignore) {

        }
    }

    public FileManager() {

    }

    public File getJavaFile(String className) {
        if (mCurrentProject == null) {
            return null;
        }
        return mCurrentProject.getJavaFiles()
                .get(className);
    }

    public File getKotlinFile(String className) {
        if (mCurrentProject == null) {
            return null;
        }
        return mCurrentProject.getKotlinFiles()
                .get(className);
    }

    public List<File> list(String packageName) {
        List<File> list = new ArrayList<>();

        for (String file : mCurrentProject.getJavaFiles().keySet()) {
            String name = file;
            if (file.endsWith(".")) {
                name = file.substring(0, file.length() - 1);
            }
            if (name.substring(0, name.lastIndexOf(".")).equals(packageName) || name.equals(packageName)) {
                list.add(mCurrentProject.getJavaFiles().get(file));
            }
        }

        if (mCurrentProject != null) {
            for (String file : mCurrentProject.getRJavaFiles().keySet()) {
                String name = file;
                if (file.endsWith(".")) {
                    name = file.substring(0, file.length() - 1);
                }
                if (name.substring(0, name.lastIndexOf(".")).equals(packageName) || name.equals(packageName)) {
                    list.add(mCurrentProject.getRJavaFiles().get(file));
                }
            }
        }
        return list;
    }

    public Cache<String, List<File>> getClassCache() {
        return classCache;
    }

    public Cache<String, List<File>> getDexCache() {
        return mDexCache;
    }

    public Cache<Void, Void> getSymbolCache() {
        return mSymbolCache;
    }

    public void addJavaFile(File javaFile) {
        String packageName = StringSearch.packageName(javaFile);
        if (packageName != null) {
            String fileName = javaFile.getName();
            if (fileName.lastIndexOf('.') != -1) {
                fileName = fileName.substring(0, fileName.lastIndexOf('.'));
            }
            packageName += "." + fileName;
            addJavaFile(javaFile, packageName);
        }
    }

    public void addJavaFile(File javaFile, String packageName) {
        mCurrentProject.getJavaFiles()
                .put(packageName, javaFile);
    }

    /**
     * Removes a java file from the indices
     *
     * @param packageName fully qualified name of the class
     */
    public void removeJavaFile(@NonNull String packageName) {
        mCurrentProject.getJavaFiles()
                .remove(packageName);
    }

    /**
     * Removes all the java files from the directory on the index and deletes the file
     *
     * @param directory The directory to delete
     * @return The files that are deleted
     * @throws IOException if the directory cannot be deleted
     */
    public List<File> deleteDirectory(File directory) throws IOException {
        List<File> javaFiles = rDelete(directory);
        FileUtils.deleteDirectory(directory);

        return javaFiles;
    }

    private List<File> rDelete(File directory) throws IOException {
        List<File> javaFiles = findFilesWithExtension(directory, ".java");
        for (File file : javaFiles) {
            if (file.isDirectory()) {
                javaFiles.addAll(rDelete(file));
                continue;
            }
            String packageName = StringSearch.packageName(file);
            if (packageName != null) {
                mCurrentProject.getJavaFiles().remove(packageName);
                FileUtils.delete(file);
            }
        }
        return javaFiles;
    }

    @VisibleForTesting
    public void setCurrentProject(Project project) {
        mCurrentProject = project;
    }

    public void openProject(@NonNull Project project) {
        if (!project.isValidProject()) {
            //TODO: throw exception
            return;
        }


        if (!project.equals(mCurrentProject)) {
            mCurrentProject = project;

            classCache = new Cache<>();
            mDexCache = new Cache<>();
            mSymbolCache = new Cache<>();
        }

        try {
            putJar(BuildModule.getAndroidJar());
        } catch (IOException ignore) {

        }

        for (File file : project.getLibraries()) {
            try {
                putJar(file);
            } catch (IOException ignore) {

            }
        }
    }

    public Project getCurrentProject() {
        return mCurrentProject;
    }

    public Set<File> getLibraries() {
        return mCurrentProject.getLibraries();
    }

    public Set<String> classpath() {
        Set<String> classpath = new HashSet<>();
        classpath.addAll(mCurrentProject.getJavaFiles().keySet());
        classpath.addAll(mCurrentProject.getRJavaFiles().keySet());
        classpath.addAll(Collections.emptySet());
        return classpath;
    }

    public Set<File> fileClasspath() {
        if (mCurrentProject != null) {
            Set<File> classpath = new HashSet<>(mCurrentProject.getJavaFiles().values());
            classpath.addAll(mCurrentProject.getLibraries());
            classpath.addAll(mCurrentProject.getRJavaFiles().values());
            return classpath;
        }
        return Collections.emptySet();
    }

    public List<String> all() {
        List<String> files = new ArrayList<>(classFiles.keySet());
        if (mCurrentProject != null) {
            files.addAll(mCurrentProject.getJavaFiles().keySet());
            files.addAll(mCurrentProject.getRJavaFiles().keySet());
            files.addAll(mCurrentProject.getKotlinFiles().keySet());
        }
        return files;
    }

    public boolean containsClass(String fullyQualifiedName) {
        return classFiles.containsKey(fullyQualifiedName);
    }

    private void putJar(File file) throws IOException {
        if (file == null) {
            return;
        }
        try (JarFile jar = new JarFile(file)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                // We only want top level classes, if it contains $ then
                // its an inner class, we ignore it
                if (entry.getName().contains("$")) {
                    continue;
                }

                String packageName = entry.getName().replace("/", ".")
                        .substring(0, entry.getName().length() - ".class".length());

                classFiles.put(packageName, file);
            }
        }
    }

    public void save(final File file, final String contents) {
        service.submit(() -> {
            if (mOpenedFiles.get(file) != null) {
                updateFile(file, contents);
            }
            try {
                FileUtils.writeStringToFile(file, contents, Charset.defaultCharset());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public synchronized String readFile(File file) {
        OpenedFile openedFile = mOpenedFiles.get(file);
        if (openedFile != null) {
            return openedFile.text;
        } else {
            String text;
            try {
                text = FileUtils.readFileToString(file, Charset.defaultCharset());
            } catch (IOException e) {
                text = "";
            }
            return text;
        }
    }

    public synchronized void updateFile(File file, String contents) {
        OpenedFile openedFile = new OpenedFile(contents, file);
        mOpenedFiles.put(file, openedFile);
    }

    public void openFile(File file) {
       String contents = readFile(file);
       updateFile(file, contents);
    }

    public void closeFile(File file, boolean save) {
        OpenedFile openedFile = mOpenedFiles.get(file);
        if (openedFile != null) {
            mOpenedFiles.remove(file);
            if (save) {
                save(file, openedFile.text);
            }
        }
    }

    public static BufferedReader bufferedReader(File file) {
        try {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        } catch (FileNotFoundException e) {
            Log.e("FileManager", Log.getStackTraceString(e));
            return new BufferedReader(new StringReader(""));
        }
    }

    public static List<File> findFilesWithExtension(File directory, String extension) {
        List<File> files = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    files.addAll(findFilesWithExtension(child, extension));
                } else {
                    if (child.getName().endsWith(extension)) {
                        files.add(child);
                    }
                }
            }
        }

        return files;
    }

    public static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }

        return dir.delete();
    }

    public static BufferedReader lines(File file) {
        return bufferedReader(file);
    }
}
