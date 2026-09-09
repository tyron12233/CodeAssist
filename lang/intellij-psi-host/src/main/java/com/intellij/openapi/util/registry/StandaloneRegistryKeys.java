package com.intellij.openapi.util.registry;

/**
 * Builds a {@link RegistryKeyDescriptor} so a registry key can be contributed from outside the platform.
 *
 * <p>{@code Registry.mutateContributedKeys} is public API, but the descriptor it takes has a package-private
 * constructor: in a running IDE the only caller is the platform's own {@code RegistryKeyBean}, which reads
 * the {@code <registryKey>} declarations out of every loaded plugin descriptor. This host embeds platform
 * jars directly and loads no plugin descriptors, so the keys declared by the Java plugin never arrive and
 * {@code Registry.is} throws {@link java.util.MissingResourceException} for them. Declaring this class in the
 * platform's own package is what lets the host supply those descriptors itself; see
 * {@code IntellijPsiHost.contributeJavaPsiRegistryKeys} for the keys and their defaults.
 */
public final class StandaloneRegistryKeys {

    private StandaloneRegistryKeys() {
    }

    /**
     * A descriptor for {@code name} carrying {@code defaultValue}, attributed to {@code pluginId}, with an
     * empty description and needing no restart.
     *
     * <p>The constructor takes the description BEFORE the default value, which is the opposite of the order
     * the fields are declared in; passing them the other way round yields a descriptor whose default is the
     * empty string, so every key silently reads as {@code false} instead of throwing. {@code RegistryKeysTest}
     * asserts the resolved values, not merely that resolution does not throw, to keep that mistake visible.
     */
    public static RegistryKeyDescriptor of(String name, String defaultValue, String pluginId) {
        return new RegistryKeyDescriptor(name, "", defaultValue, false, false, pluginId);
    }
}
