package com.tyron.kotlin.completion.core.resolve.lang.kotlin

import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.model.KotlinEnvironment
import com.tyron.kotlin.completion.core.resolve.lang.java.structure.CodeAssistJavaElementUtil
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndex
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinClassFinder
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.deserialization.MetadataPackageFragment
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import java.io.InputStream

class CodeAssistVirtualFileFinder(
    private val javaProject: KotlinModule,
    private val scope: GlobalSearchScope
): VirtualFileFinder() {

//    private val index: JvmDependenciesIndex
//        get() = KotlinEnvironment.getEnvironment(javaProject)

    override fun findMetadata(classId: ClassId): InputStream? {
        assert(!classId.isNestedClass) { "Nested classes are not supported here: $classId" }

        return findBinaryClass(
            classId,
            classId.shortClassName.asString() + MetadataPackageFragment.DOT_METADATA_FILE_EXTENSION)?.inputStream
    }

    override fun findSourceOrBinaryVirtualFile(classId: ClassId): VirtualFile? {
        TODO("Not yet implemented")
    }

    override fun hasMetadataPackage(fqName: FqName): Boolean {
//        var found = false
//
//        val index = KotlinEnvironment.getEnvironment(javaProject).index
//
//        index.traverseDirectoriesInPackage(fqName, continueSearch = { dir, _ ->
//            found = found or dir.children.any { it.extension == MetadataPackageFragment.METADATA_FILE_EXTENSION }
//            !found
//        })
//        return found
        return false
    }

    override fun findBuiltInsData(packageFqName: FqName): InputStream? {
//        val fileName = BuiltInSerializerProtocol.getBuiltInsFileName(packageFqName)
//
//        // "<builtins-metadata>" is just a made-up name
//        // JvmDependenciesIndex requires the ClassId of the class which we're searching for, to cache the last request+result
//        val classId = ClassId(packageFqName, Name.special("<builtins-metadata>"))
//
//        return index.findClass(classId, acceptedRootTypes = JavaRoot.OnlyBinary) { dir, _ ->
//            dir.findChild(fileName)?.check(VirtualFile::isValid)
//        }?.check { it in scope && it.isValid }?.inputStream
        return null
    }

    override fun findVirtualFileWithHeader(classId: ClassId): VirtualFile? {
//        val type = javaProject.findType(classId.packageFqName.asString(), classId.relativeClassName.asString())
//        if (type == null || !isBinaryKotlinClass(type)) return null
//
//        val resource = type.resource // if resource != null then it exists in the workspace and then get absolute path
//        val path = if (resource != null) resource.location else type.path
//
//        val eclipseProject = javaProject
////        In the classpath we can have either path to jar file ot to the class folder
////        Therefore in path might be location to the jar file or to the class file
//        return when {
//            isClassFileName(path.toOSString()) -> KotlinEnvironment.getEnvironment(eclipseProject).getVirtualFile(path)
//
//            KotlinEnvironment.getEnvironment(eclipseProject).isJarFile(path) -> {
//                val relativePath = "${type.fullyQualifiedName.replace('.', '/')}.class"
//                KotlinEnvironment.getEnvironment(eclipseProject).getVirtualFileInJar(path, relativePath)
//            }
//
//            else -> throw IllegalArgumentException("Virtual file not found for $path")
//        }
        TODO()
    }

    private fun isClassFileName(toOSString: Any): Boolean {
        return true
    }

    private fun classFileName(jClass: JavaClass): String {
        val outerClass = jClass.outerClass ?: return jClass.name.asString()
        return classFileName(outerClass) + "$" + jClass.name.asString()
    }

    private fun findBinaryClass(classId: ClassId, fileName: String): VirtualFile? =
//        index.findClass(classId, acceptedRootTypes = JavaRoot.OnlyBinary) { dir, rootType ->
//            dir.findChild(fileName)?.check(VirtualFile::isValid)
//        }?.check { it in scope }
        TODO()

    override fun findKotlinClassOrContent(javaClass: JavaClass): KotlinClassFinder.Result? {
        return null
//        val fqName = javaClass.fqName ?: return null
//
//        val classId = CodeAssistJavaElementUtil.computeClassId((javaClass as EclipseJavaClassifier<*>).binding) ?: return null
//        if (classId == null) return null
//
//        var file = findVirtualFileWithHeader(classId)
//        if (file == null) return null
//
//        if (javaClass.outerClass != null) {
//            // For nested classes we get a file of the containing class, to get the actual class file for A.B.C,
//            // we take the file for A, take its parent directory, then in this directory we look for A$B$C.class
//            file = file.getParent().findChild("${classFileName(javaClass)}.class")
//            if (file != null) throw IllegalStateException("Virtual file not found for $javaClass")
//        }

//        return KotlinBinaryClassCache.getKotlinBinaryClassOrClassFileContent(file!!)
    }
}

class CodeAssistVirtualFileFinderFactory(private val project: KotlinModule) :
    VirtualFileFinderFactory {

    override fun create(project: Project, module: ModuleDescriptor) =
        VirtualFileFinderFactory.getInstance(project).create(project, module)

    override fun create(scope: GlobalSearchScope): VirtualFileFinder = CodeAssistVirtualFileFinder(project, scope)
}

fun <T: Any> T.check(predicate: (T) -> Boolean): T? = if (predicate(this)) this else null