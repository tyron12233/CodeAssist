package com.tyron.builder.compiler;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Main entry point for building apk files, this class does all
 *  * the necessary operations for building apk files such as compiling resources,
 *  * compiling java files, dexing and merging
 */
@Deprecated
public class ApkBuilder {

    public interface OnResultListener {
        void onComplete(boolean success, String message);
    }

    public interface TaskListener {
        void onTaskStarted(String name, String message, int progress);
    }
}
