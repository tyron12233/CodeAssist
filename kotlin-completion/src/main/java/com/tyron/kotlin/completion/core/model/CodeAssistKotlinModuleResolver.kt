package com.tyron.kotlin.completion.core.model

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.load.java.structure.JavaAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver

class CodeAssistKotlinModuleResolver: JavaModuleResolver {
    override fun checkAccessibility(
        fileFromOurModule: VirtualFile?,
        referencedFile: VirtualFile,
        referencedPackage: FqName?
    ): JavaModuleResolver.AccessError? = null

    override fun getAnnotationsForModuleOwnerOfClass(classId: ClassId): List<JavaAnnotation>? {
        return null
    }
}