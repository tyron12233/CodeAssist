package com.tyron.code.parser;
import java.util.HashMap;
import java.io.File;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.Enumeration;
import java.io.IOException;
import android.widget.Toast;
import com.tyron.code.MainActivity;
import com.tyron.code.ApplicationLoader;
import java.util.List;
import java.util.Set;
import com.tyron.code.util.Decompress;

/**
 * Class responsible for caching java files for fast
 * lookup later whenever we need to.
 */
public class FileManager {
    
    private static FileManager INSTANCE = null;
    
    public static FileManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FileManager();
        }
        return INSTANCE;
    }
    
    FileManager() {
        
    }
    
    // Map of parsed java files with their fully qualified name as key
    private final Map<String, File> files = new HashMap<>();
    
    public Set<String> all() {
        return files.keySet();
    }
    
    void putJar(File file) throws IOException {
        JarFile jar = new JarFile(file);
        Enumeration<JarEntry> entries = jar.entries();
        while(entries.hasMoreElements()) {
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
           

            files.put(packageName, file);
        }
    }
    
    public File getAndroidJar() {
        File jarFile = new File(ApplicationLoader.applicationContext
                .getFilesDir(), "rt.jar");
        if (jarFile.exists()) {
            return jarFile;
        } else {
            Decompress.unzipFromAssets(ApplicationLoader.applicationContext,
                    "rt.zip",
                    jarFile.getParentFile().getAbsolutePath());          
            return jarFile;
        }
    }
}
