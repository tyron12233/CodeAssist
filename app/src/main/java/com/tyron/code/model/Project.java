package com.tyron.code.model;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.util.Map;
import java.util.HashMap;
import com.tyron.code.util.StringSearch;
import android.util.Log;
import javax.lang.model.SourceVersion;
import com.tyron.code.util.Decompress;
import com.tyron.code.ApplicationLoader;

/**
 * Class for storing project data, directories and files
 */
public class Project {
    
    public final File mRoot;
    
    public Map<String, File> javaFiles = new HashMap<>();
    public List<String> jarFiles = new ArrayList<>();
    
    /**
     * Creates a project object from specified root
     */
    public Project(File root) {
        mRoot = root;
        
        findJavaFiles(new File(root, "app/src/main/java"));
    }
    
    private void findJavaFiles(File file) {
        File[] files = file.listFiles();
        
        if (files != null) {
            for (File child : files) {
                if (child.isDirectory()) {
                    findJavaFiles(child);
                } else {
                    if (child.getName().endsWith(".java")) {
                        String packageName = StringSearch.packageName(child);
                        Log.d("PROJECT FIND JAVA", "Found " + child.getAbsolutePath());
                        if (packageName.isEmpty()) {
                            Log.d("Error package empty", child.getAbsolutePath());
                        } else {
                            if (SourceVersion.isName(packageName + "." + child.getName().replace(".java", ""))) {
                                javaFiles.put(packageName + "." + child.getName().replace(".java", ""), child);
                            }
                        }
                    }
                }
            }
        }
    }
    
    public Map<String, File> getJavaFiles() {
        javaFiles.clear();
        findJavaFiles(getJavaDirectory());
        return javaFiles;
    }
    
    public List<File> getLibraries() {
        List<File> libraries = new ArrayList<>();
        
        File libPath = new File(mRoot, "app/libs");
        
        Log.d("PROJECT LIBRARIES", "" + libPath);
        
        if (libPath.listFiles() == null) {
            return libraries;
        }
        
        for (File file : libPath.listFiles()) {
            if (file.getName().endsWith(".jar")) {
                libraries.add(file);
                Log.d("PROJECT LIBRARIES", "Adding " + file.getName());
            }
        }
        
        return libraries;
    }
    /**
     * Used to check if this project contains the required directories
     * such as app/src/main/java, resources and others
     */
    public boolean isValidProject() {
        File check = new File(mRoot, "app/src/main/java");
        
        if (!check.exists()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Creates a new project configured at mRoot, returns true if the project
     * has been created, false if not.
     */
    public boolean create() {
        
        // this project already exists
        if (isValidProject()) {
            return false;
        }
        
        File java = getJavaDirectory();
        
        if (!java.mkdirs()) {
            return false;
        }
        
		if (!getResourceDirectory().mkdirs()) {
			return false;
		}
		
        Decompress.unzipFromAssets(ApplicationLoader.applicationContext, "test_project.zip",
                java.getAbsolutePath());
		
        if (!getLibraryDirectory().mkdirs()) {
            return false;
        }
        
        if (!getBuildDirectory().mkdirs()) {
            return false;
        }
        
        return true;
    }
	
	public File getResourceDirectory() {
		return new File(mRoot, "app/src/main/res");
	}
    
    public File getJavaDirectory() {
        return new File(mRoot, "app/src/main/java");
    }
    
    public File getLibraryDirectory() {
        return new File(mRoot, "app/libs");
    }
    
    public File getBuildDirectory() {
        return new File(mRoot, "app/build");
    }
}
