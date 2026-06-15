package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModelStore
import java.nio.file.Files

/** The Java module type the demo uses (real ones ship in a `java-support` plugin). */
object JavaLibModuleType : ModuleType {
    override val id = "java-lib"
    override val displayName = "Java Library"
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/**
 * Generates a small multi-module Java project for testing the IDE: `app → util → core` (api-exported),
 * so completion exercises direct, transitive, and JDK-platform resolution. Writes both the model
 * (`.platform/` + `module.toml`) and `.java` sources to disk.
 */
object SampleProject {

    fun generate(store: ProjectModelStore, javaLib: ModuleType, languageLevel: LanguageLevel = LanguageLevel.JAVA_17) {
        store.workspace.beginModification().apply {
            addProject("demo", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", javaLib).apply {
                this.languageLevel = languageLevel
                addSourceSet(mainSources())
            }
            addModule("util", javaLib).apply {
                this.languageLevel = languageLevel
                addSourceSet(mainSources())
                addDependency(ModuleDependency(ModuleId("core"), DependencyScope.API, exported = true))
            }
            addModule("app", javaLib).apply {
                this.languageLevel = languageLevel
                addSourceSet(mainSources())
                addDependency(ModuleDependency(ModuleId("util"), DependencyScope.API, exported = true))
            }
            commit()
        }

        write(store, "core/src/main/java/com/example/core/Greeter.java", GREETER)
        write(store, "core/src/main/java/com/example/core/StringUtils.java", STRING_UTILS)
        write(store, "util/src/main/java/com/example/util/Formatter.java", FORMATTER)
        write(store, "app/src/main/java/com/example/app/Main.java", MAIN)
    }

    private fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)))

    private fun write(store: ProjectModelStore, relPath: String, content: String) {
        val file = store.rootPath.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }

    private val GREETER = """
        package com.example.core;

        /** Greets people. Try completing on a Greeter instance, or on the String it returns. */
        public class Greeter {
            public String greet(String name) {
                return "Hello, " + name + "!";
            }
        }
    """

    private val STRING_UTILS = """
        package com.example.core;

        public final class StringUtils {
            private StringUtils() {}

            public static String repeat(String s, int times) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < times; i++) sb.append(s);
                return sb.toString();
            }

            public static boolean isBlank(String s) {
                return s == null || s.trim().isEmpty();
            }
        }
    """

    private val FORMATTER = """
        package com.example.util;

        import com.example.core.Greeter;
        import com.example.core.StringUtils;

        /** Lives in :util, which depends on :core. Completing `greeter.` resolves across modules. */
        public class Formatter {
            private final Greeter greeter = new Greeter();

            public String format(String name) {
                String message = greeter.greet(name);
                return message.toUpperCase();
            }

            public String banner(String text) {
                return StringUtils.repeat("=", 10) + " " + text + " " + StringUtils.repeat("=", 10);
            }
        }
    """

    private val MAIN = """
        package com.example.app;

        import com.example.util.Formatter;

        /** Lives in :app -> :util -> :core. Completing `formatter.` resolves the :util type. */
        public class Main {
            public static void main(String[] args) {
                Formatter formatter = new Formatter();
                System.out.println(formatter.format("World"));
                System.out.println(formatter.banner("CodeAssist"));
            }
        }
    """
}
