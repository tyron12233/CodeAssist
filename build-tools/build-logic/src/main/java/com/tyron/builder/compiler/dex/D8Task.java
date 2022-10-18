
package com.tyron.builder.compiler.dex;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts class files into dex files and merges them in the process
 */
@SuppressWarnings("NewApi")
public class D8Task extends Task<JavaModule> {

	private static final String TAG = D8Task.class.getSimpleName();

	public D8Task(Project project, AndroidModule module, ILogger logger) {
		super(project, module, logger);
	}

	@Override
	public String getName() {
		return TAG;
	}

	@Override
	public void prepare(BuildType type) throws IOException {

	}

	@Override
	public void run() throws IOException, CompilationFailedException {
		compile();
	}

	public void compile() throws CompilationFailedException {
		try {
			getLogger().debug("Dexing libraries.");
			ensureDexedLibraries();

			getLogger().debug("Merging dexes and source files");

			List<Path> libraryDexes = getLibraryDexes();

			D8Command command = D8Command.builder(new DexDiagnosticHandler(getLogger(), getModule()))
					.addClasspathFiles(getModule().getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
					.setMinApiLevel(21)
					.addLibraryFiles(getLibraryFiles())
					.addProgramFiles(getClassFiles(new File(getModule().getBuildDirectory(), "bin/classes")))
					.addProgramFiles(libraryDexes)
					.setOutput(new File(getModule().getBuildDirectory(), "bin").toPath(), OutputMode.DexIndexed)
					.build();
			D8.run(command);

		} catch (com.android.tools.r8.CompilationFailedException e) {
			throw new CompilationFailedException(e);
		}
	}

	/**
	 * Ensures that all libraries of the project has been dex-ed
	 * @throws com.android.tools.r8.CompilationFailedException if the compilation has failed
	 */
	protected void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
		List<File> libraries = getModule().getLibraries();

		for (File lib : libraries) {
			File parentFile = lib.getParentFile();
			if (parentFile == null) {
				continue;
			}
			File[] libFiles = lib.getParentFile().listFiles();
			if (libFiles == null) {
				if (!lib.delete()) {
					getLogger().warning("Failed to delete " + lib.getAbsolutePath());
				}
			} else {
				File dex = new File(lib.getParentFile(), "classes.dex");
				if (dex.exists()) {
					continue;
				}
				if (lib.exists()) {
					getLogger().debug("Dexing jar " + parentFile.getName());
					D8Command command = D8Command.builder(new DexDiagnosticHandler(getLogger(), getModule()))
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
		path.add(getModule().getBootstrapJarFile().toPath());
		path.add(getModule().getLambdaStubsJarFile().toPath());
		return path;
	}

	/**
	 * Retrieves a list of all libraries dexes including the extra dex files if it has one
	 * @return list of all dex files
	 */
	private	 List<Path> getLibraryDexes() {
		List<Path> dexes = new ArrayList<>();
		for (File file : getModule().getLibraries()) {
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

}
