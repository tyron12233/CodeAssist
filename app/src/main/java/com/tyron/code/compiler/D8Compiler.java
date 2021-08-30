package com.tyron.code.compiler;

import android.util.Log;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.OutputMode;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Converts class files into dex files and merges them in the process
 */
@SuppressWarnings("NewApi")
public class D8Compiler {

	private static final String TAG = D8Compiler.class.getSimpleName();

	private final ILogger logViewModel;
	private final Project mProject;
	private final DiagnosticsHandler diagnosticsHandler = new DiagnosticHandler();
	
	public D8Compiler(ILogger model, Project project) {
		logViewModel = model;
		mProject = project;
	}

	ExecutorService service = Executors.newSingleThreadExecutor();
	
	public void compile() throws CompilationFailedException {
		try {
			ensureDexedLibraries();

			logViewModel.debug("Dexing and merging sources.");

			List<Path> userLibraries = mProject.getLibraries().stream().map(File::toPath).collect(Collectors.toList());

			D8Command command = D8Command.builder(diagnosticsHandler)
					.addClasspathFiles(userLibraries)
					.setMinApiLevel(21)
					.addLibraryFiles(getLibraryFiles())
					.addProgramFiles(getClassFiles(new File(mProject.getBuildDirectory(), "bin/classes")))
					.addProgramFiles(userLibraries)
					.setOutput(new File(mProject.getBuildDirectory(), "bin").toPath(), OutputMode.DexIndexed)
					.build();
			D8.run(command, service);

			// wait for all tasks to finish
			service.shutdown();
			service.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
		} catch (com.android.tools.r8.CompilationFailedException | InterruptedException e) {
			throw new com.tyron.code.util.exception.CompilationFailedException(e);
		}
	}

	/**
	 * Ensures that all libraries of the project has been dex-ed
	 * @throws com.android.tools.r8.CompilationFailedException if the compilation has failed
	 */
	private void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
		Set<File> libraries = mProject.getLibraries();

		outer : for (File lib : libraries) {
			File parentFile = lib.getParentFile();
			if (parentFile == null) {
				continue;
			}
			File[] libFiles = lib.getParentFile().listFiles();
			if (libFiles == null) {
				if (!lib.delete()) {
					logViewModel.warning("Failed to delete " + lib.getAbsolutePath());
				}
			} else {
				for (File libFile : libFiles) {
					if (libFile.getName().startsWith("classes") &&
							libFile.getName().endsWith(".dex")) {
						continue outer;
					}
				}
				if (lib.exists()) {
					Log.d(TAG, "Dexing jar " + lib.getParentFile().getName());
					D8Command command = D8Command.builder(diagnosticsHandler)
							.addLibraryFiles(getLibraryFiles())
							.addClasspathFiles(libraries.stream().map(File::toPath).collect(Collectors.toList()))
							.setMinApiLevel(21)
							.addProgramFiles(lib.toPath())
							.setMode(CompilationMode.RELEASE)
							.setOutput(lib.getParentFile().toPath(), OutputMode.DexIndexed)
							.build();
					D8.run(command, service);
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

	private class DiagnosticHandler implements DiagnosticsHandler {
		@Override
		public void error(Diagnostic diagnostic) {
			logViewModel.error(diagnostic.getDiagnosticMessage());
		}

		@Override
		public void warning(Diagnostic diagnostic) {
			logViewModel.warning(diagnostic.getDiagnosticMessage());
		}

		@Override
		public void info(Diagnostic diagnostic) {

		}

		@Override
		public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel diagnosticsLevel, Diagnostic diagnostic) {
			Log.d("DiagnosticHandler", diagnostic.getDiagnosticMessage());
			return null;
		}
	}
}
