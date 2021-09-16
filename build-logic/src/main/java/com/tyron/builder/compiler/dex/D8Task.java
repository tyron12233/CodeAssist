package com.tyron.builder.compiler.dex;

import android.util.Log;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts class files into dex files and merges them in the process
 */
@SuppressWarnings("NewApi")
public class D8Task extends Task {

	private static final String TAG = D8Task.class.getSimpleName();

	protected final DiagnosticsHandler diagnosticsHandler = new DiagnosticHandler();
	protected ILogger logViewModel;
	protected Project mProject;

	@Override
	public String getName() {
		return TAG;
	}

	@Override
	public void prepare(Project project, ILogger logger) throws IOException {
		mProject = project;
		logViewModel = logger;
	}

	@Override
	public void run() throws IOException, CompilationFailedException {
		compile();
	}

	public void compile() throws CompilationFailedException {
		try {
			logViewModel.debug("Dexing libraries.");
			long startTime = System.currentTimeMillis();
			ensureDexedLibraries();
			Log.d("D8Compiler", "Dexing libraries took " + (System.currentTimeMillis() - startTime) + " ms");

			logViewModel.debug("Merging dexes and source files");

			startTime = System.currentTimeMillis();
			List<Path> libraryDexes = getLibraryDexes();

			D8Command command = D8Command.builder(diagnosticsHandler)
					.addClasspathFiles(mProject.getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
					.setMinApiLevel(21)
					.addLibraryFiles(getLibraryFiles())
					.addProgramFiles(getClassFiles(new File(mProject.getBuildDirectory(), "bin/classes")))
					.addProgramFiles(libraryDexes)
					.setOutput(new File(mProject.getBuildDirectory(), "bin").toPath(), OutputMode.DexIndexed)
					.build();
			D8.run(command);

			Log.d("D8Compiler", "Merging dex files took " + (System.currentTimeMillis() - startTime) + " ms");

		} catch (com.android.tools.r8.CompilationFailedException e) {
			throw new CompilationFailedException(e);
		}
	}

	/**
	 * Ensures that all libraries of the project has been dex-ed
	 * @throws com.android.tools.r8.CompilationFailedException if the compilation has failed
	 */
	protected void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
		Set<File> libraries = mProject.getLibraries();

		Log.d(TAG, "Dexing libraries");

		for (File lib : libraries) {
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
				File dex = new File(lib.getParentFile(), "classes.dex");
				if (dex.exists()) {
					continue;
				}
				if (lib.exists()) {
					logViewModel.debug("Dexing jar " + parentFile.getName());
					D8Command command = D8Command.builder(diagnosticsHandler)
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

	/**
	 * Retrieves a list of all libraries dexes including the extra dex files if it has one
	 * @return list of all dex files
	 */
	private	 List<Path> getLibraryDexes() {
		List<Path> dexes = new ArrayList<>();
		for (File file : mProject.getLibraries()) {
			File parent = file.getParentFile();
			if (parent != null) {
				File[] dexFiles = parent.listFiles(file1 -> file1.getName().endsWith(".dex"));
				if (dexFiles != null) {
					dexes.addAll(Arrays.stream(dexFiles).map(File::toPath).collect(Collectors.toList()));
				}
			}
		}
		return dexes;
	}

	public static List<Path> getClassFiles(File root) {
		List<Path> paths = new ArrayList<>();

		File[] files = root.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					paths.addAll(getClassFiles(file));
				} else {
					if (file.getName().endsWith(".class")) {
						paths.add(file.toPath());
					}
				}
			}
		}
		return paths;
	}

	public class DiagnosticHandler implements DiagnosticsHandler {
		@Override
		public void error(Diagnostic diagnostic) {
			logViewModel.error(wrap(diagnostic));
		}

		@Override
		public void warning(Diagnostic diagnostic) {
			logViewModel.warning(wrap(diagnostic));
		}

		@Override
		public void info(Diagnostic diagnostic) {
			logViewModel.info(wrap(diagnostic));
		}

		@Override
		public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel diagnosticsLevel, Diagnostic diagnostic) {
			Log.d("DiagnosticHandler", diagnostic.getDiagnosticMessage());
			return null;
		}

		private DiagnosticWrapper wrap(Diagnostic diagnostic) {
			DiagnosticWrapper wrapper = new DiagnosticWrapper();
			wrapper.setMessage(diagnostic.getDiagnosticMessage());
			return wrapper;
		}
	}
}
