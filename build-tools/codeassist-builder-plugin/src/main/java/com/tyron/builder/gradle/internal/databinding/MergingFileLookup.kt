package com.tyron.builder.gradle.internal.databinding

import android.databinding.tool.LayoutXmlProcessor
import com.android.ide.common.blame.MergingLog
import com.android.ide.common.blame.SourceFile
import java.io.File

/**
 * Implementation of [LayoutXmlProcessor.OriginalFileLookup] over a resource merge blame file.
 */
class MergingFileLookup(private val resourceBlameLogDir: File) : LayoutXmlProcessor.OriginalFileLookup {
    override fun getOriginalFileFor(file: File): File? {
        val input = SourceFile(file)
        val original = mergingLog.find(input)
        return if (input === original) {
            null
        } else original.sourceFile
    }

    private val mergingLog: MergingLog by lazy {
        MergingLog(resourceBlameLogDir)
    }
}
