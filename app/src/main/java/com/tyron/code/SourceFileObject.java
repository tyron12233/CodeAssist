package com.tyron.code;
import java.io.File;
import javax.tools.SimpleJavaFileObject;
import javax.tools.JavaFileObject;
import java.io.IOException;
import com.tyron.code.parser.FileManager;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;

public class SourceFileObject extends SimpleJavaFileObject {
	
	public Path mFile;
	private Instant modified;
	
	public SourceFileObject(Path file) {
		this(file, Instant.EPOCH);
	}
	
	public SourceFileObject(Path file, Instant modified) {
		super(file.toUri(), JavaFileObject.Kind.SOURCE);
		
		mFile = file;
		this.modified = modified;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
		return FileManager.readFile(mFile.toFile());
	}
	
	@Override
    public Kind getKind() {
        String name = mFile.getFileName().toString();
        return kindFromExtension(name);
    }

    private static Kind kindFromExtension(String name) {
        for (Kind candidate : Kind.values()) {
            if (name.endsWith(candidate.extension)) {
                return candidate;
            }
        }
        return null;
    }
	
	@Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        return mFile.getFileName().toString().equals(simpleName + kind.extension);
    }
	
	@Override
	public URI toUri() {
		return mFile.toUri();
	}

	@Override
	public long getLastModified() {
		return modified.toEpochMilli();
	}
	
	@Override
	public String toString() {
		return mFile.toString();
	}
}
