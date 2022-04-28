package com.tyron.common.util;

import java.io.StringWriter;
import java.util.List;
import java.util.Scanner;

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
            mWriter.write(e.getMessage());
        }
        return mWriter.toString();
    }

    public String getLog() {
        return mWriter.toString();
    }
}
