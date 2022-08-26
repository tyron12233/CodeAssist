package com.tyron.code.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.google.common.base.Throwables;
import com.tyron.common.util.FileUtilsEx;

import org.apache.commons.io.FileUtils;
import org.gradle.launcher.daemon.bootstrap.DaemonMain;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A service that runs the {@link org.gradle.launcher.daemon.bootstrap.GradleDaemon} in a
 * separate process
 */
public class GradleDaemonService extends Service {
    public GradleDaemonService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        try {
            String dir = intent.getStringExtra("dir");
            File currentDir = new File(dir);

            File daemonInput = new File(currentDir, "daemonInput");
            FileUtilsEx.createFile(daemonInput);

            File daemonOutput = new File(currentDir, "daemonOutput");
            FileUtilsEx.createFile(daemonOutput);

            System.setIn(FileUtils.openInputStream(daemonInput));
            System.setOut(new PrintStream(FileUtils.openOutputStream(daemonOutput, true)));
        } catch (IOException e) {
            originalOut.println("Failed to connect to DaemonClient" +
                               Throwables.getStackTraceAsString(e));
            System.exit(1);
        }

        new Thread(() -> {
            DaemonMain daemonMain = new DaemonMain();
            daemonMain.run(new String[]{GradleVersion.current().getVersion()});
        }, "GradleDaemonThread").start();

        return START_NOT_STICKY;
    }
}