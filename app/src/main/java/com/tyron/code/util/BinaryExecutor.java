package com.tyron.code.util;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Scanner;
import java.util.List;

public class BinaryExecutor {
    private static final String TAG = BinaryExecutor.class.getSimpleName();

    private final ProcessBuilder mProcess = new ProcessBuilder();
    private final StringWriter mWriter = new StringWriter();

    public void setCommands(List<String> arrayList) {
        mProcess.command(arrayList);
    }

    public String execute() {

        try {
			Process process = mProcess.start();
            Scanner scanner = new Scanner(process.getErrorStream());
            while (scanner.hasNextLine()) {
                mWriter.append(scanner.nextLine());
                mWriter.append(System.lineSeparator());
            }

			process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, String.valueOf(new PrintWriter(mWriter)));
        }
        return mWriter.toString();
    }

    public String getLog() {
        return mWriter.toString();
    }
}
