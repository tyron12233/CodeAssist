package com.tyron.builder.model;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class CodeAssistAndroidLibrary extends CodeAssistLibrary {


    private List<File> compileJarFiles;
    private File publicResources;
    private File symbolFile;
    private File resStaticLibrary;
    private File resFolder;

    public void setCompileJarFiles(List<File> compileJarFiles) {
        this.compileJarFiles = compileJarFiles;
    }

    public List<File> getCompileJarFiles() {
        return compileJarFiles;
    }


    public void setPublicResources(File publicResources) {
        this.publicResources = publicResources;
    }

    public File getPublicResources() {
        return publicResources;
    }

    public void setSymbolFile(File symbolFile) {
        this.symbolFile = symbolFile;
    }

    /**
     * @return The R.txt file containing the resource symbols of this library
     */
    public File getSymbolFile() {
        return symbolFile;
    }

    public void setResStaticLibrary(File resStaticLibrary) {
        this.resStaticLibrary = resStaticLibrary;
    }

    /**
     * Returns the res.apk file for namespaced libraries
     * returns null if the file does not exist
     */
    @Nullable
    public File getResStaticLibrary() {
        if (resStaticLibrary == null || !resStaticLibrary.exists()) {
            return null;
        }
        return resStaticLibrary;
    }

    public void setResFolder(File resFolder) {
        this.resFolder = resFolder;
    }

    public File getResFolder() {
        return resFolder;
    }
}
