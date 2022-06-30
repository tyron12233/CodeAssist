package com.tyron.builder.api.artifacts.configurations;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.capabilities.Capability;

import java.util.Collection;
import java.util.Set;

public class Configurations {
    public static ImmutableSet<String> getNames(Collection<Configuration> configurations) {
        if (configurations.isEmpty()) {
            return ImmutableSet.of();
        }
        if (configurations.size() == 1) {
            return ImmutableSet.of(configurations.iterator().next().getName());
        }
        ImmutableSet.Builder<String> names = new ImmutableSet.Builder<>();
        for (Configuration configuration : configurations) {
            names.add(configuration.getName());
        }
        return names.build();
    }

    public static Set<Capability> collectCapabilities(Configuration configuration, Set<Capability> out, Set<Configuration> visited) {
        if (visited.add(configuration)) {
            out.addAll(configuration.getOutgoing().getCapabilities());
            for (Configuration parent : configuration.getExtendsFrom()) {
                collectCapabilities(parent, out, visited);
            }
        }
        return out;
    }

    public static String uploadTaskName(String configurationName) {
        return "upload" + StringUtils.capitalize(configurationName);
    }
}
