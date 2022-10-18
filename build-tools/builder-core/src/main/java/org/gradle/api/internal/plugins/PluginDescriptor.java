package org.gradle.api.internal.plugins;

import org.gradle.util.GUtil;

import java.net.URL;
import java.util.Properties;

public class PluginDescriptor {

    private final URL propertiesFileUrl;

    public PluginDescriptor(URL propertiesFileUrl) {
        this.propertiesFileUrl = propertiesFileUrl;
    }

    public String getImplementationClassName() {
        Properties properties = GUtil.loadProperties(propertiesFileUrl);
        return properties.getProperty("implementation-class");
    }

    public URL getPropertiesFileUrl() {
        return propertiesFileUrl;
    }

    @Override
    public String toString() {
        return propertiesFileUrl.toString();
    }
}
