package com.tyron.code.compiler;
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

	private Project mProject;

	public AAPT2Compiler(Project project) {
		mProject = project;
	}

	public void run() throws IOException, CompilationFailedException {
		compileProject();
	}

	private void compileProject() throws IOException, CompilationFailedException {
		List<String> args = new ArrayList<>();
		args.add(getBinary().getAbsolutePath());
		args.add("compile");
		args.add("--dir");
		args.add(mProject.getResourceDirectory().getAbsolutePath());
		args.add("-o");
		args.add(createNewFile(getOutputPath(), "project.zip").getAbsolutePath());
		
		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (!exec.execute().isEmpty()) {
			throw new CompilationFailedException(exec.getLog());
		}
	}
	
	private void link() throws IOException, CompilationFailedException {
		List<String> args = new ArrayList<>();
		
		args.add(getBinary().getAbsolutePath());
		args.add("link");
		args.add("--allow-reserved-package-id");
        args.add("--no-version-vectors");
        args.add("--no-version-transitions");
        args.add("--auto-add-overlay");
        args.add("--min-sdk-version");
		args.add("21");
		args.add("--target-sdk-version");
		args.add("30");
		args.add("-I");
		args.add(FileManager.getInstance().getAndroidJar().getAbsolutePath());
		
		File[] resources = new File(getOutputPath().getAbsoluteFile(), "res").listFiles();
		if (resources != null) {
			for (File resource : resources) {
				if (resource.isDirectory()) {
					continue;
				}
				args.add("-R");
				args.add(resource.getAbsolutePath());
			}
		}
		
		args.add("--java");
		args.add(new File(getOutputPath(), "gen").getAbsolutePath());
		args.add("-o");
		args.add(createNewFile(getOutputPath(), "generated.apk.res").getAbsolutePath());

		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (exec.execute() != null) {
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
