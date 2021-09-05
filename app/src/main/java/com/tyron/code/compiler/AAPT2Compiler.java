package com.tyron.code.compiler;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.compiler.symbol.SymbolProcessor;
import com.tyron.code.model.Project;

import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tyron.code.service.ILogger;
import com.tyron.code.util.BinaryExecutor;
import com.tyron.code.util.exception.CompilationFailedException;
import com.tyron.code.parser.FileManager;

public class AAPT2Compiler {

	private static final Pattern MANIFEST_PACKAGE = Pattern.compile("\\s*(package)\\s*(=)\\s*(\")([a-zA-Z0-9.]+)(\")");
	private static final String TAG = AAPT2Compiler.class.getSimpleName();

	private final Project mProject;
	private final ILogger mLogger;

	public AAPT2Compiler(ILogger log, Project project) {
		mLogger = log;
		mProject = project;
	}

	public void run() throws IOException, CompilationFailedException {
		compileProject();
		link();
	}

	private void compileProject() throws IOException, CompilationFailedException {

		mLogger.debug("Compiling project resources.");

		FileManager.deleteDir(getOutputPath());
		FileManager.deleteDir(new File(mProject.getBuildDirectory(), "gen"));

		List<String> args = new ArrayList<>();
		args.add(getBinary().getAbsolutePath());
		args.add("compile");
		args.add("--dir");
		args.add(mProject.getResourceDirectory().getAbsolutePath());
		args.add("-o");
		args.add(createNewFile(getOutputPath(), "project.zip").getAbsolutePath());

		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (!exec.execute().trim().isEmpty()) {
			throw new CompilationFailedException(exec.getLog());
		}

		compileLibraries();

	}

	private void compileLibraries() throws IOException, CompilationFailedException {

		mLogger.debug("Compiling libraries.");

		for (File file : mProject.getLibraries()) {
			File parent = file.getParentFile();
			if (parent == null) {
				throw new IOException("Library folder doesn't exist");
			}
			File[] files = parent.listFiles();
			if (files == null) {
				continue;
			}

			for (File inside : files) {
				if (inside.isDirectory() && inside.getName().equals("res")) {
					Log.d(TAG, "Compiling library " + parent.getName());

					List<String> args = new ArrayList<>();
					args.add(getBinary().getAbsolutePath());
					args.add("compile");
					args.add("--dir");
					args.add(inside.getAbsolutePath());
					args.add("-o");
					args.add(createNewFile(getOutputPath(), parent.getName() + ".zip").getAbsolutePath());

					BinaryExecutor exec = new BinaryExecutor();
					exec.setCommands(args);
					if (!exec.execute().trim().isEmpty()) {
						throw new CompilationFailedException(exec.getLog());
					}
				}
			}
		}
	}
	
	private void link() throws IOException, CompilationFailedException {
		mLogger.debug("Linking resources");

		List<String> args = new ArrayList<>();
		
		args.add(getBinary().getAbsolutePath());
		args.add("link");
		args.add("-I");
		args.add(FileManager.getInstance().getAndroidJar().getAbsolutePath());
		args.add("--allow-reserved-package-id");
        args.add("--no-version-vectors");
        args.add("--no-version-transitions");
        args.add("--auto-add-overlay");
        args.add("--min-sdk-version");
		args.add(String.valueOf(mProject.getMinSdk()));
		args.add("--target-sdk-version");
		args.add(String.valueOf(mProject.getTargetSdk()));
		
		File[] resources = getOutputPath().listFiles();
		if (resources != null) {
			for (File resource : resources) {
				if (resource.isDirectory()) {
					continue;
				}
				if (!resource.getName().endsWith(".zip")) {
					continue;
				}
				args.add("-R");
				args.add(resource.getAbsolutePath());
			}
		}
		args.add("--java");
		File gen = new File(mProject.getBuildDirectory(), "gen");
		if (!gen.exists()) {
			if (!gen.mkdirs()) {
				throw  new CompilationFailedException("Failed to create gen folder");
			}
		}
		args.add(gen.getAbsolutePath());

		args.add("--manifest");
		args.add(mProject.getManifestFile().getAbsolutePath());

		args.add("-o");
		args.add(getOutputPath().getParent() + "/generated.apk.res");

		args.add("--output-text-symbols");
		File file = new File(getOutputPath(), "R.txt");
		Files.deleteIfExists(file.toPath());
		if (!file.createNewFile()) {
			throw new IOException("Unable to create R.txt file");
		}
		args.add(file.getAbsolutePath());

		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (!exec.execute().trim().isEmpty()) {
			throw new CompilationFailedException(exec.getLog());
		}

		// generate Symbols
		SymbolProcessor processor = new SymbolProcessor(mProject, mLogger);
		processor.run();
	}
	
	private File getOutputPath() throws IOException {
		File file = new File(mProject.getBuildDirectory(), "bin/res");
		if (!file.exists()) {
			if (!file.mkdirs()) {
				throw new IOException("Failed to get resource directory");
			}
		}
		return file;
	}

	private File createNewFile(File parent, String name) throws IOException {
        File createdFile = new File(parent, name);
        if (!parent.exists()) {
			if (!parent.mkdirs()) {
				throw new IOException("Unable to create directories");
			}
		}
		if (!createdFile.createNewFile()) {
			throw new IOException("Unable to create file " + name);
		}
		return createdFile;
	}

	/**
	 * Retrieves the package names of libraries of has a resource file
	 * @return list of package names in a form of string separated by ":" for use with AAPT2 directly
	 */
	private String getPackageNames() {
		StringBuilder builder = new StringBuilder();

		// getLibraries return list of classes.jar, get its parent
		for (File library : mProject.getLibraries()) {
			File parent = library.getParentFile();

			String packageName = getPackageName(parent);
			if (packageName != null) {
				builder.append(packageName);
				builder.append(":");
			}
		}

		return builder.toString();
	}

	/**
	 * Gets the package name of the library if it needs a R.java file
	 * @param library the directory in which the res folder or classes.jar is located
	 * @return Package name of the library, null if it has no resource folder
	 */
	public static String getPackageName(File library) {

		File resFolder = new File(library, "res");
		if (!resFolder.exists()) {
			return null;
		}

		File manifestFile = new File(library, "AndroidManifest.xml");
		if (!manifestFile.exists()) {
			// This library doesn't have a resource file
			return null;
		}

		String manifestString = FileManager.readFile(manifestFile);
		Matcher matcher = MANIFEST_PACKAGE.matcher(manifestString);
		if (matcher.find()) {
			return matcher.group(4);
		}
		return null;
	}

	private static File getBinary() throws IOException {
		File check = new File(
			ApplicationLoader.applicationContext.getApplicationInfo().nativeLibraryDir,
			"libaapt2.so"
		);
		if (check.exists()) {
			return check;
		}

		throw new IOException("AAPT2 Binary not found");
	}
}
