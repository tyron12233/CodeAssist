package com.tyron.builder.compiler.resource;


import com.tyron.builder.BuildModule;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.BinaryExecutor;
import com.tyron.common.util.Decompress;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public class AidlTask extends Task<AndroidModule> {

	
    public AidlTask(AndroidModule project, ILogger logger) {
        super(project, logger);
    }

    private static final String TAG = "AidlTask";

    private File mGenDir;
    private File mAidlDir;
    File frameworkFile = new File(BuildModule.getContext().getFilesDir(), "framework.aidl");
	
    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mGenDir = new File(getModule().getBuildDirectory(), "/gen");
		mAidlDir = new File(getModule().getRootFile(), "src/main/aidl");
		}
	
        

    public void run() throws IOException, CompilationFailedException {
      framework();     
		int i = 1;  
	  if(!mAidlDir.exists()) 
			getLogger().debug("No aidl source files, Skipping compilation.");
		  
		else 
		//while (i < 6) { 					
			aidl();
  // i++;
	//	}
		}
    

   
    private void framework() throws IOException {
        getLogger().debug("Preparing framework.");

             if (!frameworkFile.exists()) {
            InputStream input = BuildModule.getContext()
				.getAssets().open("framework.aidl");
            OutputStream output = new FileOutputStream(
				new File(BuildModule.getContext().getFilesDir(), "framework.aidl"));
            IOUtils.copy(input, output);
        }
    }

	
    private void aidl() throws CompilationFailedException, IOException {
        getLogger().debug("Compiling aidl files.");
        		List<String> args = new ArrayList<>();
         args.add(getBinary().getAbsolutePath());
		args.add("-p");
		args.add(frameworkFile.getAbsolutePath());
		args.add("-o");
		args.add(mGenDir.getAbsolutePath());
		args.add("-I");
		args.add(mAidlDir.getAbsolutePath());
		args.add(mAidlDir.getAbsolutePath() + "/com/test/AidlTest.aidl");
		
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
	
	
	}
