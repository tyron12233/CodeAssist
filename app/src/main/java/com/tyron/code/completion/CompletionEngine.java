package com.tyron.code.completion;
import com.tyron.code.CompilerProvider;
import com.tyron.code.JavaCompilerService;
import com.tyron.code.parser.FileManager;
import java.util.stream.Collectors;
import java.util.Collections;
import com.tyron.code.model.CompletionList;
import java.io.File;
import com.tyron.code.compiler.CompileBatch;

public class CompletionEngine {
	
	public static CompletionEngine Instance = null;
	
	public static CompletionEngine getInstance() {
		if (Instance == null) {
			Instance = new CompletionEngine();
		}
		return Instance;
	}
	
	private JavaCompilerService mProivider;
	
	private CompletionEngine() {
		getCompiler();
	}
	
	public JavaCompilerService getCompiler() {
		if (mProivider != null) {
			return mProivider;
		}
		
		mProivider = new JavaCompilerService(
			FileManager.getInstance().getCurrentProject().getJavaFiles().values().stream().collect(Collectors.toSet()),
			Collections.emptySet(),
			Collections.emptySet()
		);
		return mProivider;
	}
	
	public synchronized CompletionList complete(File file, long cursor) {
		try {
			return new CompletionProvider(mProivider).complete(file, cursor);
		} catch (RuntimeException | AssertionError e) {
			CompileBatch batch = mProivider.cachedCompile;
			mProivider = null;
			getCompiler();
			
		}
		return new CompletionList();
	}
}
