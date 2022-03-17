package com.tyron.kotlin.completion.core.resolve.lang.java.resolver

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.javax.inject.Inject
import org.jetbrains.kotlin.load.java.components.JavaResolverCache
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.lazy.ResolveSessionUtils

class CodeAssistTraceBasedJavaResolverCache : JavaResolverCache {
    private lateinit var trace: BindingTrace
    private lateinit var resolveSession: ResolveSession

    @Inject
    fun setTrace(trace: BindingTrace) {
        this.trace = trace
    }

    @Inject
    fun setResolveSession(resolveSession: ResolveSession) {
        this.resolveSession = resolveSession
    }

    override fun getClassResolvedFromSource(fqName: FqName): ClassDescriptor? {
        return trace[BindingContext.FQNAME_TO_CLASS_DESCRIPTOR, fqName.toUnsafe()] ?: findInPackageFragments(fqName)
    }

    override fun recordMethod(p0: JavaMember, descriptor: SimpleFunctionDescriptor) {
    }

    override fun recordConstructor(element: JavaElement, descriptor: ConstructorDescriptor) {
    }

    override fun recordField(field: JavaField, descriptor: PropertyDescriptor) {
    }

    override fun recordClass(javaClass: JavaClass, descriptor: ClassDescriptor) {
    }

    // Copied from org.jetbrains.kotlin.load.java.components.LazyResolveBasedCache
    private fun findInPackageFragments(fullFqName: FqName): ClassDescriptor? {
        var fqName = if (fullFqName.isRoot) fullFqName else fullFqName.parent()

        while (true) {
            val packageDescriptor = resolveSession.getPackageFragment(fqName)
            if (packageDescriptor == null) break

            val result = ResolveSessionUtils.findClassByRelativePath(
                packageDescriptor.getMemberScope(), fullFqName.tail(fqName))
            if (result != null) return result

            if (fqName.isRoot) break
            fqName = fqName.parent()
        }

        return null
    }
}