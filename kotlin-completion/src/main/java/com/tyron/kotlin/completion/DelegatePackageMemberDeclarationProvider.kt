package com.tyron.kotlin.completion

import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.lazy.data.KtScriptInfo
import org.jetbrains.kotlin.resolve.lazy.declarations.PackageMemberDeclarationProvider
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

open class DelegatePackageMemberDeclarationProvider(var delegate: PackageMemberDeclarationProvider) : PackageMemberDeclarationProvider {
    // Can't use Kotlin delegate feature because of inability to change delegate object in runtime (KT-5870)

    override fun getAllDeclaredSubPackages(nameFilter: (Name) -> Boolean) = delegate.getAllDeclaredSubPackages(nameFilter)

    override fun getPackageFiles() = delegate.getPackageFiles()

    override fun containsFile(file: KtFile) = delegate.containsFile(file)

    override fun getDeclarations(
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ) = delegate.getDeclarations(kindFilter, nameFilter)

    override fun getFunctionDeclarations(name: Name) = delegate.getFunctionDeclarations(name)

    override fun getPropertyDeclarations(name: Name) = delegate.getPropertyDeclarations(name)

    override fun getDestructuringDeclarationsEntries(name: Name): Collection<KtDestructuringDeclarationEntry> =
        delegate.getDestructuringDeclarationsEntries(name)

    override fun getClassOrObjectDeclarations(name: Name) = delegate.getClassOrObjectDeclarations(name)

    override fun getScriptDeclarations(name: Name): Collection<KtScriptInfo> = delegate.getScriptDeclarations(name)

    override fun getTypeAliasDeclarations(name: Name) = delegate.getTypeAliasDeclarations(name)

    override fun getDeclarationNames() = delegate.getDeclarationNames()
}