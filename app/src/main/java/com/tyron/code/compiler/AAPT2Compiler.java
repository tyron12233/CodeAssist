package com.tyron.code.compiler;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.model.Project;
import java.util.List;
import java.util.ArrayList;
import com.tyron.code.util.BinaryExecutor;
import com.tyron.code.util.exception.CompilationFailedException;
import com.tyron.code.parser.FileManager;

public class AAPT2Compiler {

	private static final String TAG = AAPT2Compiler.class.getSimpleName();

	private Project mProject;

	public AAPT2Compiler(Project project) {
		mProject = project;
	}

	public void run() throws IOException, CompilationFailedException {
		compileProject();
		link();
	}

	private void compileProject() throws IOException, CompilationFailedException {

		Log.d(TAG, "Compiling project");

		FileManager.deleteDir(getOutputPath());

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

		for (File file : mProject.getLibraries()) {
			File parent = file.getParentFile();
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
		args.add("21");
		args.add("--target-sdk-version");
		args.add("30");
		
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

		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (!exec.execute().trim().isEmpty()) {
			throw new CompilationFailedException(exec.getLog());
		}
	}
	
	private File getOutputPath() {
		File file = new File(mProject.getBuildDirectory(), "bin/res");
		file.mkdirs();
		return file;
	}

	private File createNewFile(File parent, String name) throws IOException {
        File createdFile = new File(parent, name);
		parent.mkdirs();
		createdFile.createNewFile();
		return createdFile;
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
