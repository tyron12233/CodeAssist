package com.tyron.builder.compiler.apk;

import com.tyron.builder.compiler.ApkSigner;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.model.Project;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;

public class SignTask extends Task {

    private File mInputApk;
    private File mOutputApk;

    @Override
    public String getName() {
        return "Sign";
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mInputApk = new File(project.getBuildDirectory(), "bin/generated.apk");
        mOutputApk = new File(project.getBuildDirectory(), "bin/signed.apk");
        if (!mInputApk.exists()) {
            throw new IOException("Unable to find generated apk file.");
        }

        logger.debug("Signing APK.");
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        ApkSigner signer = new ApkSigner(mInputApk.getAbsolutePath(),
                mOutputApk.getAbsolutePath(), ApkSigner.Mode.TEST);

        try {
            signer.sign();
        } catch (Exception e) {
            throw new CompilationFailedException(e);
        }
    }
}
