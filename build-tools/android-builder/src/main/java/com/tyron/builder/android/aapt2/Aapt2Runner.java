package com.tyron.builder.android.aapt2;

import com.tyron.builder.util.GUtil;
import com.tyron.common.util.BinaryExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Aapt2Runner {

    private static final String COMPILE_COMMAND = "compile";
    private static final String LINK_COMMAND = "link";

    private final File binary;

    public Aapt2Runner(Aapt2BinaryProvider provider) {
        binary = GUtil.uncheckedCall(provider::getBinary);
    }

    public int link(List<String> args) {
        return -1;
    }

    public int compile(List<String> args) {
        List<String> arguments = new ArrayList<>();
        arguments.add(binary.getAbsolutePath());
        arguments.add(COMPILE_COMMAND);
        arguments.addAll(args);

        BinaryExecutor binaryExecutor = new BinaryExecutor();
        binaryExecutor.setCommands(arguments);
        String execute = binaryExecutor.execute();
        return 0;
    }
}
