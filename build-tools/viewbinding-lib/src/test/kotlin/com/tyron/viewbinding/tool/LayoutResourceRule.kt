package com.tyron.viewbinding.tool

import com.tyron.viewbinding.tool.store.LayoutFileParser
import com.tyron.viewbinding.tool.store.ResourceBundle
import com.tyron.viewbinding.tool.util.RelativizableFile
import com.tyron.viewbinding.tool.writer.BaseLayoutModel
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

class LayoutResourceRule(
    private val appPackage: String = "com.example",
    private val useAndroidX: Boolean = true,
    private val viewBindingEnabled: Boolean = false,
    private val dataBindingEnabled: Boolean = false
) : TestRule {
    private val temporaryFolder = object : TemporaryFolder() {
        override fun before() {
            super.before()
            realResDir = newFolder("res")
            strippedResDir = newFolder("res-stripped")
        }
    }

    private lateinit var realResDir: File
    private lateinit var strippedResDir: File

    override fun apply(base: Statement, description: Description): Statement {
        return temporaryFolder.apply(base, description)
    }

    fun write(name: String, folder: String, content: String) {
        require(folder == "layout" || folder.startsWith("layout-"))
        require(!name.endsWith(".xml"))

        val folderDir = File(realResDir, folder)
        folderDir.mkdir()
        val layoutFile = File(folderDir, "$name.xml")
        layoutFile.writeText(content)
    }

    fun parse(): Map<String, BaseLayoutModel> {
        val resourceBundle = ResourceBundle(appPackage, useAndroidX)
        realResDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val strippedFile = File(strippedResDir, file.toRelativeString(realResDir))
            val bundle = LayoutFileParser.parseXml(
                RelativizableFile.fromAbsoluteFile(file),
                appPackage,
                null,
                viewBindingEnabled
            )
            if (bundle != null) {
                resourceBundle.addLayoutBundle(bundle, true)
            }
        }
        resourceBundle.validateAndRegisterErrors()

        return resourceBundle.allLayoutFileBundlesInSource
            .groupBy(ResourceBundle.LayoutFileBundle::getFileName)
            .mapValues { BaseLayoutModel(it.value) }
    }
}
