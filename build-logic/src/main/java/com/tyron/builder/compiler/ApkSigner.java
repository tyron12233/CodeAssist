package com.tyron.builder.compiler;

import com.tyron.builder.BuildModule;
import com.tyron.common.util.Decompress;

import java.util.ArrayList;
import java.io.File;


public class ApkSigner {

    public static class Mode {
        public static int TEST = 0;
        //ToDo add more modes
    }


    private final ArrayList<String> commands ;
    private final String mApkInputPath ;
    private final String mApkOutputPath;

    public ApkSigner(String inputPath ,String outputPath,int mode){
        commands = new ArrayList<>();
        mApkInputPath = inputPath;
        mApkOutputPath = outputPath;

    }

    //TODO: Adjust min and max sdk
    public void sign() throws Exception{
        commands.add("sign");
        commands.add("--key");
        commands.add(getTestKeyFilePath());
        commands.add("--cert");
        commands.add(getTestCertFilePath());
        commands.add("--min-sdk-version");
        commands.add("21");
        commands.add("--max-sdk-version");
        commands.add("30");
        commands.add("--out");
        commands.add(mApkOutputPath);
        commands.add("--in");
        commands.add(mApkInputPath);
        com.android.apksigner.ApkSignerTool.main(commands.toArray(new String[0]));
    }


    private String getTestKeyFilePath() {
        File check = new File(BuildModule.getContext().getFilesDir() + "/temp/testkey.pk8");

        if (check.exists()) {
            return check.getAbsolutePath();
        }

        Decompress.unzipFromAssets(BuildModule.getContext(), "testkey.pk8.zip", check.getParentFile().getAbsolutePath());

        return check.getAbsolutePath();
    }

    private String getTestCertFilePath() {
        File check = new File(BuildModule.getContext().getFilesDir() + "/temp/testkey.x509.pem");

        if (check.exists()) {
            return check.getAbsolutePath();
        }

        Decompress.unzipFromAssets(BuildModule.getContext(), "testkey.x509.pem.zip", check.getParentFile().getAbsolutePath());

        return check.getAbsolutePath();
    }

}
