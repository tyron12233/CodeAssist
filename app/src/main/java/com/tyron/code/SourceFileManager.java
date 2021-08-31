package com.tyron.code;

import com.sun.tools.javac.api.JavacTool;
import com.tyron.code.parser.FileManager;
import com.tyron.code.util.StringSearch;


import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public class SourceFileManager extends ForwardingJavaFileManager {
	
	public SourceFileManager() {
		super(createDelegateFileManager());
	}
	
	private static StandardJavaFileManager createDelegateFileManager() {
        JavacTool compiler = JavacTool.create();
        return compiler.getStandardFileManager(SourceFileManager::logError, null, Charset.defaultCharset());
    }
	
	private static void logError(Diagnostic<?> error) {
        
    }

	@Override
	public Iterable<JavaFileObject> list(JavaFileManager.Location location, String packageName, Set kinds, boolean recurse) throws IOException {
		
		if (location == StandardLocation.SOURCE_PATH) {
			Stream<JavaFileObject> stream = FileManager.getInstance()
					.list(packageName).stream()
					.map(this::asJavaFileObject);
			return stream::iterator;
		}
		return super.list(location, packageName, kinds, recurse);
	}
	
	private JavaFileObject asJavaFileObject(File file) {
		return new SourceFileObject(file.toPath());
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
        // FileStore shadows disk
        if (location == StandardLocation.SOURCE_PATH) {
            String packageName = StringSearch.mostName(className);
            String simpleClassName = StringSearch.lastName(className);
            for (File f : FileManager.getInstance().list(packageName)) {
                if (f.getName().equals(simpleClassName + kind.extension)) {
                    return new SourceFileObject(f.toPath());
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
	
}
