package com.tyron.builder.model;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import com.google.common.base.Strings;
import com.tyron.builder.project.api.JavaModule;

import org.apache.commons.io.FileUtils;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

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
	private final JavaModule mProject;
	
	public SourceFileObject(Path file) {
		this(file, null, null, null);
	}

	public SourceFileObject(Path file, JavaModule project) {
		this(file, null, null, project);
	}

	public SourceFileObject(Path file, JavaModule project, Instant modified) {
		this(file, null, modified, project);
	}

	public SourceFileObject(Path file, String contents, Instant modified) {
		this(file, contents, modified, null);
	}
	
	public SourceFileObject(Path file, String contents, Instant modified, JavaModule project) {
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
				return replaceContents(String.valueOf(fileContent.get()));
			}
		}

		if (mContents != null) {
			return replaceContents(mContents);
		}
		try {
			String s = FileUtils.readFileToString(mFile.toFile(), Charset.defaultCharset());
			return replaceContents(s);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * By default, the java compiler treats tabs as 8 spaces.
	 * A work around for this is to replace the tabs with the number of space
	 * of tabs from the editor
	 */
	private String replaceContents(String contents) {
		return contents;
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
