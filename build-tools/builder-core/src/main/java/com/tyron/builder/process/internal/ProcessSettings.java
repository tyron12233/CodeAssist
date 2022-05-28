package com.tyron.builder.process.internal;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface ProcessSettings {

    File getDirectory();

    String getCommand();

    List<String> getArguments();

    Map<String, String> getEnvironment();

    boolean getRedirectErrorStream();

}
