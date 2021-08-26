package com.tyron.code.completion;

import android.util.Log;

import com.tyron.code.JavaCompilerService;
import com.tyron.code.model.CompletionList;
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
				new HashSet<>(FileManager.getInstance().getCurrentProject().getJavaFiles().values()),
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
	public synchronized CompletionList complete(File file, long cursor) {
		// Do not request for completion if we're indexing
		if (mIndexing) {
			return CompletionList.EMPTY;
		}

		try {
			return new CompletionProvider(mProivider).complete(file, cursor);
		} catch (RuntimeException | AssertionError e) {
			Log.d(TAG, "Completion failed: " + e.getMessage() + " Clearing cache.");
			mProivider = null;
			getCompiler();
			
		}
		return CompletionList.EMPTY;
	}
}
