package com.tyron.code.completion;

import android.annotation.SuppressLint;
import android.util.Log;

import com.tyron.code.ApplicationLoader;
import com.tyron.code.CompileTask;
import com.tyron.code.JavaCompilerService;
import com.tyron.code.model.CompletionList;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;

public class CompletionEngine {
	private static final String TAG = CompletionEngine.class.getSimpleName();

	public static CompletionEngine Instance = null;
	
	public static CompletionEngine getInstance() {
		if (Instance == null) {
			Instance = new CompletionEngine();
		}
		return Instance;
	}
	
	private JavaCompilerService mProivider;
	private boolean mIndexing;
	
	private CompletionEngine() {
		getCompiler();
	}
	
	public JavaCompilerService getCompiler() {
		if (mProivider != null) {
			return mProivider;
		}
		
		mProivider = new JavaCompilerService(
				FileManager.getInstance().fileClasspath(),
				Collections.emptySet(),
				Collections.emptySet()
			);
		return mProivider;
	}

	/**
	 * Disable subsequent completions
	 */
	public void setIndexing(boolean val) {
		mIndexing = val;
	}

	public boolean isIndexing() {
		return mIndexing;
	}

	@SuppressLint("NewApi")
	public void index(Project project, Runnable callback) {
		setIndexing(true);

		JavaCompilerService compiler = getCompiler();

		for (File file : new HashSet<>(project.javaFiles.values())) {
			try {
				 CompileTask task = compiler.compile(file.toPath());
				 task.close();
			} catch (Throwable ignored) {

			}
		}
		setIndexing(false);
		if (callback != null) {
			ApplicationLoader.applicationHandler.post(callback);
		}
	}

	public synchronized CompletionList complete(File file, long cursor) {
		// Do not request for completion if we're indexing
		if (mIndexing) {
			return CompletionList.EMPTY;
		}

		try {
			return new CompletionProvider(mProivider).complete(file, cursor);
		} catch (RuntimeException | AssertionError e) {
			Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
			mProivider = null;
			index(FileManager.getInstance().getCurrentProject(), null );
		}
		return CompletionList.EMPTY;
	}
}
