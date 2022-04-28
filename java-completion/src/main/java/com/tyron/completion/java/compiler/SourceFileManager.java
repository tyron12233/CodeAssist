package com.tyron.completion.java.compiler;

import android.text.TextUtils;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.util.StringSearch;

import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import com.sun.tools.javac.api.JavacTool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Project mProject;
    private Module mCurrentModule;

	public SourceFileManager(Project project) {
		super(createDelegateFileManager());
        mProject = project;
	}
	
	private static StandardJavaFileManager createDelegateFileManager() {
        JavacTool compiler = JavacTool.create();
        return compiler.getStandardFileManager(SourceFileManager::logError, Locale.getDefault(), Charset.defaultCharset());
    }
	
	private static void logError(Diagnostic<?> error) {
        
    }

    public void setCurrentModule(Module module) {
	    mCurrentModule = module;
    }

	@Override
	public Iterable<JavaFileObject> list(JavaFileManager.Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
		if (location == StandardLocation.SOURCE_PATH) {
		    List<File> found = new ArrayList<>();
            for (Module module : mProject.getModules()) {
                found.addAll(list(module, packageName));
            }
            Stream<JavaFileObject> stream = found.stream()
                    .map(this::asJavaFileObject);
            return stream.collect(Collectors.toList());
		}
		return super.list(location, packageName, kinds, recurse);
	}
	
	private JavaFileObject asJavaFileObject(File file) {
		return new SourceFileObject(file.toPath(), (JavaModule) mProject.getModule(file));
	}
	
	@Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (location == StandardLocation.SOURCE_PATH) {
            SourceFileObject source = (SourceFileObject) file;
            String packageName = StringSearch.packageName(source.mFile.toFile());
            String className = removeExtension(source.mFile.getFileName().toString());
            if (!packageName.isEmpty()) className = packageName + "." + className;
            return className;
        } else {
            return super.inferBinaryName(location, file);
        }
    }

    private String removeExtension(String fileName) {
        int lastDot = fileName.lastIndexOf(".");
        return (lastDot == -1 ? fileName : fileName.substring(0, lastDot));
    }
	
	@Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.SOURCE_PATH || super.hasLocation(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
	throws IOException {
	    if (TextUtils.isEmpty(className)) {
	        return null;
        }
        // FileStore shadows disk
        if (location == StandardLocation.SOURCE_PATH) {
            String packageName = StringSearch.mostName(className);
            String simpleClassName = StringSearch.lastName(className);
            for (File f : list(mCurrentModule, packageName)) {
                if (f.getName().equals(simpleClassName + kind.extension)) {
                    return new SourceFileObject(f.toPath(), (JavaModule) mCurrentModule);
                }
            }
            // Fall through to disk in case we have .jar or .zip files on the source path
        }
        return super.getJavaFileForInput(location, className, kind);
    }
	
	@Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        if (location == StandardLocation.SOURCE_PATH) {
            return null;
        }
        return super.getFileForInput(location, packageName, relativeName);
    }

    @Override
    public boolean contains(Location location, FileObject fileObject) throws IOException {
        System.out.println("Contains called at location " + location + " " +
                "\n" +
                "file object: " + fileObject);
        return super.contains(location, fileObject);
    }

    public void setLocation(Location location, Iterable<? extends  File> path) throws IOException {
	    fileManager.setLocation(location, path);
    }

    public static List<File> list(Module module, String packageName) {
	    if (!(module instanceof JavaModule)) {
	        return Collections.emptyList();
        }
	    JavaModule javaModule = (JavaModule) module;

	    List<File> list = new ArrayList<>();
        Map<String, File> toSearch = new HashMap<>(javaModule.getJavaFiles());
        toSearch.putAll(javaModule.getInjectedClasses());

        for (String file : toSearch.keySet()) {
            String name = file;
            if (file.endsWith(".")) {
                name = file.substring(0, file.length() - 1);
            }
            if (!name.startsWith(packageName)) {
                continue;
            }
            if (name.substring(0, name.lastIndexOf(".")).equals(packageName) || name.equals(packageName)) {
                list.add(toSearch.get(file));
            }
        }
        return list;
    }
}
