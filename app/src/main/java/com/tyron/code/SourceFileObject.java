package com.tyron.code;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import javax.tools.SimpleJavaFileObject;
import javax.tools.JavaFileObject;
import com.tyron.code.parser.FileManager;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

@SuppressLint("NewApi")
public class SourceFileObject extends SimpleJavaFileObject {
	
	public Path mFile;
	private final Instant modified;
	
	public SourceFileObject(Path file) {
		this(file, Instant.EPOCH);
	}
	
	public SourceFileObject(Path file, Instant modified) {
		super(file.toUri(), JavaFileObject.Kind.SOURCE);
		
		mFile = file;
		this.modified = modified;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) {
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
	
	@NonNull
	@Override
	public String toString() {
		return mFile.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SourceFileObject that = (SourceFileObject) o;
		return Objects.equals(mFile, that.mFile);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mFile);
	}
}
