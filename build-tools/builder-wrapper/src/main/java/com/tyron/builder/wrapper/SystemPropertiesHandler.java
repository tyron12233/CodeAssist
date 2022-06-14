package com.tyron.builder.wrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class SystemPropertiesHandler {
    static final String SYSTEM_PROP_PREFIX = "systemProp.";

    public static Map<String, String> getSystemProperties(File propertiesFile) {
        Map<String, String> propertyMap = new HashMap<String, String>();
        if (!propertiesFile.isFile()) {
            return propertyMap;
        }
        Properties properties = new Properties();
        try {
            FileInputStream inStream = new FileInputStream(propertiesFile);
            try {
                properties.load(inStream);
            } finally {
                inStream.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error when loading properties file=" + propertiesFile, e);
        }

        for (Object argument : properties.keySet()) {
            if (argument.toString().startsWith(SYSTEM_PROP_PREFIX)) {
                String key = argument.toString().substring(SYSTEM_PROP_PREFIX.length());
                if (key.length() > 0) {
                    propertyMap.put(key, properties.get(argument).toString());
                }
            }
        }
        return propertyMap;
    }
}
