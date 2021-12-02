package com.tyron.builder.compiler;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.BuildModule;
import com.tyron.common.util.Decompress;

import java.io.File;
import java.util.ArrayList;


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
    public void sign() throws Exception {
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
        if (sTestKeyFile != null) {
            return sTestKeyFile.getAbsolutePath();
        }
        File check = new File(BuildModule.getContext().getFilesDir() + "/temp/testkey.pk8");

        if (check.exists()) {
            sTestKeyFile = check;
            return check.getAbsolutePath();
        }

        Decompress.unzipFromAssets(BuildModule.getContext(), "testkey.pk8.zip",
                check.getParentFile().getAbsolutePath());

        return check.getAbsolutePath();
    }

    private String getTestCertFilePath() {
        if (sTestCertFile != null) {
            return sTestCertFile.getAbsolutePath();
        }

        File check = new File(BuildModule.getContext().getFilesDir() +
                "/temp/testkey.x509.pem");

        if (check.exists()) {
            sTestCertFile = check;
            return check.getAbsolutePath();
        }

        Decompress.unzipFromAssets(BuildModule.getContext(), "testkey.x509.pem.zip",
                check.getParentFile().getAbsolutePath());

        return check.getAbsolutePath();
    }

    @VisibleForTesting
    public static void setTestKeyFile(File file) {
        sTestKeyFile = file;
    }

    @VisibleForTesting
    public static void setTestCertFile(File file) {
        sTestCertFile = file;
    }

    private static File sTestKeyFile;
    private static File sTestCertFile;

}
