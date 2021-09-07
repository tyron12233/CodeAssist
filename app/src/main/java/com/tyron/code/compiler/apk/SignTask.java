package com.tyron.code.compiler.apk;

import com.tyron.code.compiler.ApkSigner;
import com.tyron.code.compiler.Task;
import com.tyron.code.model.Project;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.exception.CompilationFailedException;

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
    public void prepare(Project project, ILogger logger) throws IOException {
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
