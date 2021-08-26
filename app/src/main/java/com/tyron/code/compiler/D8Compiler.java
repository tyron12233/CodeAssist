package com.tyron.code.compiler;
import com.tyron.code.model.Project;
import java.util.List;
import java.util.ArrayList;
import com.tyron.code.parser.FileManager;

/**
 * Converts class files into dex files and merges them in the process
 */
public class D8Compiler {
	
	private final Project mProject;
	
	public D8Compiler(Project project) {
		mProject = project;
	}
	
	public void compile() {
		
	}
	
	private String[] getArgs() {
		List<String> args = new ArrayList<>();
		
		args.add("--output");
		args.add(mProject.getBuildDirectory().getAbsolutePath() + "/bin");
		args.add("--lib");
		args.add(FileManager.getInstance().getAndroidJar().getAbsolutePath());
		args.add("--lib");
		args.add(FileManager.getInstance().getLambdaStubs().getAbsolutePath());
		
		return args.toArray(new String[0]);
	}
}
