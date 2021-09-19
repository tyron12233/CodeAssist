package com.tyron.layoutpreview;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import dalvik.system.DexClassLoader;

public class PreviewClassLoader extends DexClassLoader {

    public static PreviewClassLoader newInstance(List<File> libraryDexes, File mainDexFile) {
        libraryDexes.add(mainDexFile);
        String classPath = libraryDexes.stream()
                .map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
        return new PreviewClassLoader(classPath, null, null, getSystemClassLoader());
    }
    public PreviewClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }


}
