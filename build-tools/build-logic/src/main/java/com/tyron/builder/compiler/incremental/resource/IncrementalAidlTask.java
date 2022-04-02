package com.tyron.builder.compiler.incremental.resource;
import com.tyron.builder.BuildModule;
import java.io.IOException;
import java.io.File;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.common.util.BinaryExecutor;
import java.util.List;
import java.util.ArrayList;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.Project;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.compiler.Task;
import java.io.BufferedReader;
import java.io.FileReader;

public class IncrementalAidlTask extends Task<AndroidModule> {

    private static final String TAG = "IncrementalAIDL";

	    public IncrementalAidlTask(Project project,
                                AndroidModule module,
                                ILogger logger
                         ) {
        super(project, module, logger);
        
    }
	
    @Override
    public String getName() {
        return TAG;
    }
	private List<File> allLibs = new ArrayList<>();
    private File aidlLibs;
    @Override
    public void prepare(BuildType type) throws IOException {
		aidlLibs = new File(getModule().getAidlDirectory().getAbsolutePath()); 
		try {
			File aar = new File(getModule().getBuildDirectory(), "/libs");
			if (aar.exists()) {
				for (File f : aar.listFiles()) {
					if (!allLibs.contains(f.getName())) {
						allLibs.add(f);
					}
				}
			
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
}
	
    public void run() throws IOException, CompilationFailedException {
		List<String> allAidl = new ArrayList<>();
        for (File f : allLibs) {
            File aidl = new File(f, ".aidl");
            if (aidl.exists()) {
                allAidl.add(aidl.getAbsolutePath());
            }
        }
              for (File f : aidlLibs.listFiles()){
            File ai = new File(f, ".aidl");
            if (ai.exists()) {
                allAidl.add(ai.getAbsolutePath());
            }
        }
		
		List<String> args = new ArrayList<>();
    	args.add(getBinary().getAbsolutePath());
        args.add("-p");
        args.add(BuildModule.getFramework().getAbsolutePath());
        args.add("-o");
        args.add(getModule().getBuildDirectory().getAbsolutePath() + "/gen");
        args.add("-I");
        args.add(aidlLibs.getAbsolutePath());
        getAllFilesOfDir(".aidl", aidlLibs.getAbsolutePath(), args);
		
        //cmd.add(aidlLibs.getAbsolutePath());
		/* for (String aidl : allAidl) {
		 cmd.add(aidl);
		 }*/
		 getLogger().debug(args.toString());
     //   System.out.println(args.toString());
		BinaryExecutor executor = new BinaryExecutor();
		 executor.setCommands(args);
		 if (!executor.execute().isEmpty()) {
		 throw new CompilationFailedException(executor.getLog());
		 }
    }

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
	
	private List<String> readFile(String str) {
        List<String> list = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(new File(str)));
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                list.add(line);
            }
            br.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void getAllFilesOfDir(String s, String path, StringBuilder sb) {
        File file = new File(path);
        for (File x : file.listFiles()) {
            if (x.isDirectory()) {
                getAllFilesOfDir(s, x.getAbsolutePath(), sb);
            } else if (x.isFile()) {
                if (x.getName().endsWith(s)) {
                    sb.append(" ");
                    sb.append(x.getAbsolutePath());
                }
            }
        }
    }

    private void getAllFilesOfDir(String s, String path, List<String> sb) {
        File file = new File(path);
        for (File x : file.listFiles()) {
            if (x.isDirectory()) {
                getAllFilesOfDir(s, x.getAbsolutePath(), sb);
            } else if (x.isFile()) {
                if (x.getName().endsWith(s)) {
					sb.add("-I");
					sb.add(x.getAbsolutePath());
                }
            }
        }
    }
	}

