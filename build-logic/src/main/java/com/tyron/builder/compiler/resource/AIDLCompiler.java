package com.tyron.builder.compiler.resource;

import android.util.Log;

import com.tyron.builder.BuildModule;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
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
import java.io.FilenameFilter;

public class AIDLCompiler {

	private static final String TAG = AIDLCompiler.class.getSimpleName();

	private final Project mProject;
	private final ILogger mLogger;
	File file = new File(mProject.getBuildDirectory(), "gen");

	
	public AIDLCompiler(ILogger log, Project project) {
		mLogger = log;
		mProject = project;
	}

	public void run() throws IOException, CompilationFailedException {
		
		compileProject();
	
		}
	private void compileProject() throws IOException, CompilationFailedException {

		mLogger.debug("Compiling project AIDL resources.");

		FileManager.deleteDir(getOutputPath());
		FileManager.deleteDir(new File(mProject.getBuildDirectory(), "gen"));
                //command :- aidl pathtoaidl file,need to make complile separate aidl files into gen
		List<String> args = new ArrayList<>();
		args.add(getBinary().getAbsolutePath());
		args.add("");
		args.add("-o");
		args.add(file.getAbsolutePath());
	
		BinaryExecutor exec = new BinaryExecutor();
		exec.setCommands(args);
		if (!exec.execute().trim().isEmpty()) {
			throw new CompilationFailedException(exec.getLog());
		}

		

	}

	
			
	/**
	 * Retrieves the package names of libraries of has a resource file
	 * @return list of package names in a form of string separated by ":" for use with AAPT2 directly
	 */
	
	/**
	 * Retrieves the package name of a manifest (.xml) file
	 * @return null if an exception occurred or cannot be determined.
	 */
	
	private static File getBinary() throws IOException {
		File check = new File(
			BuildModule.getContext().getApplicationInfo().nativeLibraryDir,
			"libaidl.so"
		);
		if (check.exists()) {
			return check;
		}

		throw new IOException("AIDL Binary not found");
	}
}
