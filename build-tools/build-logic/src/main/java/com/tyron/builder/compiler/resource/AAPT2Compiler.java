package com.tyron.builder.compiler.resource;

import android.util.Log;

import com.android.tools.aapt2.Aapt2Jni;
import com.tyron.builder.BuildModule;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogUtils;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.BinaryExecutor;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		long start = System.currentTimeMillis();

		compileProject();
		link();

		Log.d(TAG, "Resource compilation took " + (System.currentTimeMillis() - start) + " ms");
	}

	private void compileProject() throws IOException, CompilationFailedException {

		mLogger.debug("Compiling project resources.");

		FileManager.deleteDir(getOutputPath());
		FileManager.deleteDir(new File(mProject.getBuildDirectory(), "gen"));

		List<String> args = new ArrayList<>();
		args.add("--dir");
		args.add(mProject.getResourceDirectory().getAbsolutePath());
		args.add("-o");
		args.add(createNewFile(getOutputPath(), "project.zip").getAbsolutePath());

		int compile = Aapt2Jni.compile(args);
		List<DiagnosticWrapper> logs = Aapt2Jni.getLogs();
		LogUtils.log(logs, mLogger);

		if (compile != 0) {
			throw new CompilationFailedException("Compilation failed, check logs for more details.");
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

			File resFolder = new File(parent, "res");
			if (!resFolder.exists() || !resFolder.isDirectory()) {
				continue;
			}

			Log.d(TAG, "Compiling library " + parent.getName());

			List<String> args = new ArrayList<>();
			args.add(getBinary().getAbsolutePath());
			args.add("compile");
			args.add("--dir");
			args.add(resFolder.getAbsolutePath());
			args.add("-o");
			args.add(createNewFile(getOutputPath(), parent.getName() + ".zip").getAbsolutePath());

			int compile = Aapt2Jni.compile(args);
			List<DiagnosticWrapper> logs = Aapt2Jni.getLogs();
			LogUtils.log(logs, mLogger);

			if (compile != 0) {
				throw new CompilationFailedException("Compilation failed, check logs for more details.");
			}
		}
	}
	
	private void link() throws IOException, CompilationFailedException {
		mLogger.debug("Linking resources");

		List<String> args = new ArrayList<>();

		args.add("-I");
		args.add(BuildModule.getAndroidJar().getAbsolutePath());
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

		int compile = Aapt2Jni.link(args);
		List<DiagnosticWrapper> logs = Aapt2Jni.getLogs();
		LogUtils.log(logs, mLogger);

		if (compile != 0) {
			throw new CompilationFailedException("Compilation failed, check logs for more details.");
		}
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
	 * Retrieves the package name of a manifest (.xml) file
	 * @return null if an exception occurred or cannot be determined.
	 */
	public static String getPackageName(File library) {
		String manifestString;
		try {
			manifestString = FileUtils.readFileToString(library, Charset.defaultCharset());
		} catch (IOException e) {
			return null;
		}
		Matcher matcher = MANIFEST_PACKAGE.matcher(manifestString);
		if (matcher.find()) {
			return matcher.group(4);
		}
		return null;
	}

	private static File getBinary() throws IOException {
		File check = new File(
			BuildModule.getContext().getApplicationInfo().nativeLibraryDir,
			"libaapt2.so"
		);
		if (check.exists()) {
			return check;
		}

		throw new IOException("AAPT2 Binary not found");
	}
}
