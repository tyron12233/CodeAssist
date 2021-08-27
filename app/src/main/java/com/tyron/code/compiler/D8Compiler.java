package com.tyron.code.compiler;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.tyron.code.model.Project;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.tyron.code.parser.FileManager;
import com.tyron.code.util.exception.CompilationFailedException;

/**
 * Converts class files into dex files and merges them in the process
 */
public class D8Compiler {

	private static final String TAG = D8Compiler.class.getSimpleName();
	
	private final Project mProject;
	
	public D8Compiler(Project project) {
		mProject = project;
	}

	ExecutorService service = Executors.newSingleThreadExecutor();
	
	public void compile() throws CompilationFailedException {
		try {
			ensureDexedLibraries();
//			D8Command command = D8Command.builder()
//					.addClasspathFiles(getClassFiles(new File(mProject.getBuildDirectory(), "bin/classes")))
//					.setMinApiLevel(26)
//					.addLibraryFiles(getLibraryFiles())
//					.build();
		} catch (com.android.tools.r8.CompilationFailedException e) {
			throw new com.tyron.code.util.exception.CompilationFailedException(e);
		}
	}

	private void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
		Set<File> libraries = mProject.getLibraries();

		outer : for (File lib : libraries) {
			File[] libFiles = lib.getParentFile().listFiles();
			if (libFiles == null) {
				lib.delete();
			} else {
				for (File libFile : libFiles) {
					if (libFile.getName().startsWith("classes") &&
							libFile.getName().endsWith(".dex")) {
						continue outer;
					}
				}
				if (lib.exists()) {
					D8Command command = D8Command.builder()
							.addLibraryFiles(getLibraryFiles())
							.addClasspathFiles(libraries.stream().map(File::toPath).collect(Collectors.toList()))
							.setMinApiLevel(21)
							.addProgramFiles(lib.toPath())
							.setMode(CompilationMode.RELEASE)
							.setOutput(lib.getParentFile().toPath(), OutputMode.DexIndexed)
							.build();
					D8.run(command);
				}
			}
		}
	}

	private List<Path> getLibraryFiles() {
		List<Path> path = new ArrayList<>();
		path.add(FileManager.getInstance().getAndroidJar().toPath());
		path.add(FileManager.getInstance().getLambdaStubs().toPath());
		return path;
	}
	@RequiresApi(api = Build.VERSION_CODES.O)
	private List<Path> getClassFiles(File root) {
		List<Path> paths = new ArrayList<>();

		File[] files = root.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					paths.addAll(getClassFiles(file));
				} else {
					paths.add(file.toPath());
				}
			}
		}
		return paths;
	}
}
