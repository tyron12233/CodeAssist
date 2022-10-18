package com.tyron.code.language.groovy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradleDelegate {
	private static final String PROJECT = "org.gradle.api.Project";
	private static final String SETTINGS = "org.gradle.api.initialization.Settings";
	// Here to support different versions of delegate types. The values of delegate
	// types are sorted by priority
	private static final Map<String, List<String>> delegateMap;

	static {
		delegateMap = new HashMap<>();
		// plugins
		delegateMap.put("application", List.of("org.gradle.api.plugins.JavaApplication",
				"org.gradle.api.plugins.ApplicationPluginConvention"));
		delegateMap.put("base", List.of("org.gradle.api.plugins.BasePluginExtension",
				"org.gradle.api.plugins.BasePluginConvention"));
		delegateMap.put("java", List.of("org.gradle.api.plugins.JavaPluginExtension",
				"org.gradle.api.plugins.JavaPluginConvention"));
		delegateMap.put("war", List.of("org.gradle.api.plugins.WarPluginConvention"));
		// basic closures
		delegateMap.put("plugins", List.of("org.gradle.plugin.use.PluginDependenciesSpec"));
		delegateMap.put("configurations", List.of("org.gradle.api.artifacts.Configuration"));
		delegateMap.put("dependencySubstitution", List.of("org.gradle.api.artifacts.DependencySubstitutions"));
		delegateMap.put("resolutionStrategy", List.of("org.gradle.api.artifacts.ResolutionStrategy"));
		delegateMap.put("artifacts", List.of("org.gradle.api.artifacts.dsl.ArtifactHandler"));
		delegateMap.put("components", List.of("org.gradle.api.artifacts.dsl.ComponentMetadataHandler"));
		delegateMap.put("modules", List.of("org.gradle.api.artifacts.dsl.ComponentModuleMetadataHandler"));
		delegateMap.put("dependencies", List.of("org.gradle.api.artifacts.dsl.DependencyHandler"));
		delegateMap.put("repositories", List.of("org.gradle.api.artifacts.dsl.RepositoryHandler"));
		delegateMap.put("publishing", List.of("org.gradle.api.publish.PublishingExtension"));
		delegateMap.put("publications", List.of("org.gradle.api.publish.PublicationContainer"));
		delegateMap.put("sourceSets", List.of("org.gradle.api.tasks.SourceSet"));
		delegateMap.put("distributions", List.of("org.gradle.api.distribution.Distribution"));
		delegateMap.put("fileTree", List.of("org.gradle.api.file.ConfigurableFileTree"));
		delegateMap.put("copySpec", List.of("org.gradle.api.file.CopySpec"));
		delegateMap.put("exec", List.of("org.gradle.process.ExecSpec"));
		delegateMap.put("files", List.of("org.gradle.api.file.ConfigurableFileCollection"));
		delegateMap.put("task", List.of("org.gradle.api.Task"));
	}

	public static Map<String, List<String>> getDelegateMap() {
		return delegateMap;
	}

	public static String getDefault() {
		return PROJECT;
	}

	public static String getSettings() {
		return SETTINGS;
	}
}