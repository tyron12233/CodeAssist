package dev.codeassist.csharp.project

import dev.codeassist.csharp.ui.CsFileIcons
import dev.ide.plugin.ui.NewFileContent
import dev.ide.plugin.ui.NewFileKind
import dev.ide.plugin.ui.NewFileTemplate
import dev.ide.plugin.ui.UiRegistration
import java.io.File

object CsNewFiles {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun getName(input: String): String {
        val name = input.trim().removeSuffix(".cs")

        require(name.isNotEmpty()) {
            "Enter a class name"
        }

        require(IDENTIFIER.matches(name)) {
            "A class name must start with a letter or _, and contain only letters, digits and _"
        }

        return name
    }

    fun csClass(name: String): String = """
            public class $name
            {

            }
        """.trimIndent()

    fun csInterface(name: String): String =
    """
            public interface $name
            {

            }
        """.trimIndent()

    fun csStruct(name: String): String =
    """
            public struct $name
            {

            }
        """.trimIndent()

    fun csRecord(name: String): String =
    """
            public record $name
            {

            }
        """.trimIndent()

    fun csEnum(name: String): String =
    """
            public enum $name
            {

            }
        """.trimIndent()

    private val CS_DIRS = setOf("src", "bin")

    fun isCsharpDirectory(dir: String): Boolean {
        val directory = File(dir)

        if (directory.name in CS_DIRS) return true

        var current: File? = directory

        while (current != null) {
            if (current.listFiles()?.any {
                    it.isFile && it.extension.equals("csproj", ignoreCase = true)
                } == true) {
                return true
            }
            current = current.parentFile
        }

        return directory.listFiles()?.any {
            it.isFile && it.extension.equals("cs", ignoreCase = true)
        } == true
    }
}

internal object CsNewFileTemplates {

    fun register(uiRegister: UiRegistration) {
        uiRegister.newFileTemplate(
            NewFileTemplate(
                id = "dev.codeassist.csharp.class",
                title = "New C# file",
                nameLabel = "Name",
                iconId = CsFileIcons.CSHARP,
                appliesTo = CsNewFiles::isCsharpDirectory,
                kinds = listOf(
                    NewFileKind(
                        id = "class",
                        label = "Class",
                        files = { _, name ->
                            val n = CsNewFiles.getName(name)
                            listOf(
                                NewFileContent(
                                    "$n.cs",
                                    CsNewFiles.csClass(n)
                                ),
                            )
                        },
                    ),
                    NewFileKind(
                        id = "interface",
                        label = "Interface",
                        files = { _, name ->
                            val n = CsNewFiles.getName(name)
                            listOf(
                                NewFileContent(
                                    "$n.cs",
                                    CsNewFiles.csInterface(n)
                                ),
                            )
                        },
                    ),
                    NewFileKind(
                        id = "struct",
                        label = "Struct",
                        files = { _, name ->
                            val n = CsNewFiles.getName(name)
                            listOf(
                                NewFileContent(
                                    "$n.cs",
                                    CsNewFiles.csStruct(n)
                                ),
                            )
                        },
                    ),
                    NewFileKind(
                        id = "record",
                        label = "Record",
                        files = { _, name ->
                            val n = CsNewFiles.getName(name)
                            listOf(
                                NewFileContent(
                                    "$n.cs",
                                    CsNewFiles.csRecord(n)
                                ),
                            )
                        },
                    ),
                    NewFileKind(
                        id = "enum",
                        label = "Enum",
                        files = { _, name ->
                            val n = CsNewFiles.getName(name)
                            listOf(
                                NewFileContent(
                                    "$n.cs",
                                    CsNewFiles.csEnum(n)
                                ),
                            )
                        },
                    )
                )
            )
        )
    }
}
