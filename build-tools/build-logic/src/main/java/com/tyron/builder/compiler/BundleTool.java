package com.tyron.builder.compiler;


import com.tyron.builder.BuildModule;
import java.io.File;
import java.util.ArrayList;
import com.android.tools.build.bundletool.BundleToolMain;

public class BundleTool {

    public static class Mode {
        public static int TEST = 0;
        //ToDo add more modes
    }


    private final ArrayList<String> commands ;
    private final String mApkInputPath ;
    private final String mApkOutputPath;

    public BundleTool(String inputPath ,String outputPath,int mode){
        commands = new ArrayList<>();
        mApkInputPath = inputPath;
        mApkOutputPath = outputPath;
    }

    //TODO: Adjust min and max sdk
    public void aab() throws Exception {
		commands.add("build-bundle");
        commands.add("--modules=" + mApkInputPath);
        commands.add("--output=" + mApkOutputPath);
	
        com.android.tools.build.bundletool.BundleToolMain.main(commands.toArray(new String[0]));
    }
	public void apk() throws Exception {
		commands.add("build-apks");
        commands.add("--bundle=" + mApkInputPath);
        commands.add("--output=" + mApkOutputPath);
        commands.add("--mode=universal");
        commands.add("--aapt2=" + BuildModule.getContext().getApplicationInfo().nativeLibraryDir + "/libaapt2.so");

		

        com.android.tools.build.bundletool.BundleToolMain.main(commands.toArray(new String[0]));
    }
	

    }
