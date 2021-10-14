package com.tyron.builder.parser;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.tyron.builder.BuildModule;
import com.tyron.builder.model.Project;
import com.tyron.common.util.Cache;
import com.tyron.common.util.Decompress;
import com.tyron.common.util.StringSearch;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
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

    private static FileManager INSTANCE = null;
    private final ExecutorService service = Executors.newFixedThreadPool(4);

    private Project mCurrentProject;

    // Map of compiled (.class) files with their fully qualified name as key
    private final Map<String, File> classFiles = new HashMap<>();
    private final Map<String, File> javaFiles = new HashMap<>();

    /**
     * Cache of java class files, keys can contain Dex files, Java class files and the values are
     * the files corresponding to it
     */
    private Cache<String, List<File>> classCache = new Cache<>();
    private Cache<String, List<File>> mDexCache = new Cache<>();
    private Cache<Void, Void> mSymbolCache = new Cache<>();

    private static File sAndroidJar;
    private static File sLambdaStubs;

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

    public FileManager() {

    }

    @VisibleForTesting
    public FileManager(File androidJar, File lambdaStubs) {
        sAndroidJar = androidJar;
        sLambdaStubs = lambdaStubs;

        try {
            putJar(androidJar);
        } catch (IOException ignore) {

        }
    }

    public File getJavaFile(String className) {
        return javaFiles.get(className);
    }

    public List<File> list(String packageName) {
        List<File> list = new ArrayList<>();

        for (String file : javaFiles.keySet()) {
            String name = file;
            if (file.endsWith(".")) {
                name = file.substring(0, file.length() - 1);
            }
            if (name.substring(0, name.lastIndexOf(".")).equals(packageName) || name.equals(packageName)) {
                list.add(javaFiles.get(file));
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
        javaFiles.put(packageName, javaFile);
    }

    /**
     * Removes a java file from the indices
     * @param packageName fully qualified name of the class
     */
    public void removeJavaFile(@NonNull String packageName) {
        javaFiles.remove(packageName);
    }

    /**
     * Removes all the java files from the directory on the index and deletes the file
     * @param directory The directory to delete
     * @throws IOException if the directory cannot be deleted
     * @return The files that are deleted
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
                this.javaFiles.remove(packageName);
                FileUtils.delete(file);
            }
        }
        return javaFiles;
    }

    public void openProject(Project project) {
        if (!project.isValidProject()) {
            //TODO: throw exception
            return;
        }
        
        mCurrentProject = project;
        classFiles.clear();
        javaFiles.clear();

        if (mCurrentProject != project) {
            classCache = new Cache<>();
            mDexCache = new Cache<>();
            mSymbolCache = new Cache<>();
        }
        
        try {
            putJar(getAndroidJar());
        } catch (IOException ignore) {

        }
        
        javaFiles.putAll(project.getJavaFiles());
        
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
        classpath.addAll(javaFiles.keySet());
        classpath.addAll(mCurrentProject.getRJavaFiles().keySet());
        classpath.addAll(Collections.emptySet());
        return classpath;
    }

    public Set<File> fileClasspath() {
        Set<File> classpath = new HashSet<>(javaFiles.values());

        if (mCurrentProject != null) {
            classpath.addAll(mCurrentProject.getLibraries());
            classpath.addAll(mCurrentProject.getRJavaFiles().values());
        }
        return classpath;
    }
    
    public List<String> all() {
        List<String> files = new ArrayList<>();
        files.addAll(javaFiles.keySet());
        files.addAll(classFiles.keySet());

        if (mCurrentProject != null) {
            files.addAll(mCurrentProject.getRJavaFiles().keySet());
        }
        return files;
    }

    public boolean containsClass(String fullyQualifiedName) {
        return classFiles.containsKey(fullyQualifiedName);
    }

    private void putJar(File file) throws IOException {
        JarFile jar = new JarFile(file);
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
    
    public void save(final File file, final String contents) {
        service.submit(() -> writeFile(file, contents));
    }

    @Deprecated
    public static String readFile(File file) {
        createNewFile(file);

        StringBuilder sb = new StringBuilder();
        BufferedReader fr = null;
        try {
            fr = bufferedReader(file);
            
            String str;
            while ((str = fr.readLine()) != null) {
                sb.append(str).append("\n");
            }
        } catch (IOException e) {
            Log.e("FileManager", e.getMessage());
        } finally {
            if (fr != null) {
                try {
                    fr.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return sb.toString();
    }
    
    private static void createNewFile(File file) {
        
        if (file.exists()) {
            return;
        }
        
        String path = file.getAbsolutePath();
        int lastSep = path.lastIndexOf(File.separator);
        if (lastSep > 0) {
            String dirPath = path.substring(0, lastSep);
            makeDir(new File(dirPath));
        }
        try {
            if (!file.exists())
                file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
    
    
    public static void makeDir(File file) {
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public static void writeFile(File file, String str) {
        createNewFile(file);
        FileWriter fileWriter = null;

        try {
            fileWriter = new FileWriter(file, false);
            fileWriter.write(str);
            fileWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fileWriter != null)
                    fileWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
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

    @VisibleForTesting
    public static void setAndroidJar(File androidJar) {
        sAndroidJar = androidJar;
    }

    public static File getAndroidJar() {
        if (sAndroidJar == null) {
            Context context = BuildModule.getContext();
            if (context == null) {
                return null;
            }

            sAndroidJar = new File(context
                    .getFilesDir(), "rt.jar");
            if (!sAndroidJar.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(),
                        "rt.zip",
                        sAndroidJar.getParentFile().getAbsolutePath());
            }
        }

        return sAndroidJar;
    }
    
    public static File getLambdaStubs() {
        if (sLambdaStubs == null) {
            sLambdaStubs = new File(BuildModule.getContext().getFilesDir(), "core-lambda-stubs.jar");

            if (!sLambdaStubs.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "lambda-stubs.zip", sLambdaStubs.getParentFile().getAbsolutePath());
            }
        }
        return sLambdaStubs;
    }
}
