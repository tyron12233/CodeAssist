package com.tyron.builder.model;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import com.tyron.builder.project.api.JavaProject;

import org.apache.commons.io.FileUtils;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.SimpleJavaFileObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

@SuppressLint("NewApi")
public class SourceFileObject extends SimpleJavaFileObject {

	public Path mFile;
	private final Instant modified;
	private final String mContents;
	private final JavaProject mProject;
	
	public SourceFileObject(Path file) {
		this(file, null, null, null);
	}

	public SourceFileObject(Path file, JavaProject project) {
		this(file, null, null, project);
	}

	public SourceFileObject(Path file, String contents, Instant modified) {
		this(file, contents, modified, null);
	}
	
	public SourceFileObject(Path file, String contents, Instant modified, JavaProject project) {
		super(file.toUri(), JavaFileObject.Kind.SOURCE);
		mContents = contents;
		mFile = file;
		this.modified = modified;
		mProject = project;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) {
		if (mProject != null) {
			Optional<CharSequence> fileContent = mProject.getFileManager().getFileContent(mFile.toFile());
			if (fileContent.isPresent()) {
				return fileContent.get();
			}
		}

		if (mContents != null) {
			return mContents;
		}
		try {
			return FileUtils.readFileToString(mFile.toFile(), Charset.defaultCharset());
		} catch (IOException e) {
			return null;
		}
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
		if (modified == null) {
			try {
				return Files.getLastModifiedTime(mFile).toMillis();
			} catch (IOException e) {
				return Instant.EPOCH.toEpochMilli();
			}
		}
		return modified.toEpochMilli();
	}
	
	@NonNull
	@Override
	public String toString() {
		return mFile.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (getClass() != o.getClass()) return false;
		SourceFileObject that = (SourceFileObject) o;
		return this.mFile.equals(that.mFile);
	}

	@Override
	public int hashCode() {
		return mFile.hashCode();
	}
}
