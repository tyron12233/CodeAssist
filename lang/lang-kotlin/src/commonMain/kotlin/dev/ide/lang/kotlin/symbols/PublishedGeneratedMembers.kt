package dev.ide.lang.kotlin.symbols

import dev.ide.lang.KotlinGeneratedMember
import dev.ide.lang.KotlinGeneratedMemberContext
import dev.ide.lang.KotlinGeneratedMemberProvider
import dev.ide.lang.KotlinSourceClass

/**
 * A published [KotlinGeneratedMemberProvider] (what an installed plugin registers on
 * `dev.ide.lang.KOTLIN_GENERATED_MEMBER_EP`) as the symbol service's own [KotlinSyntheticMemberProvider].
 *
 * The published side speaks a read-only view of the class and plain member descriptions, so the source model's
 * [RawClass]/[RawCallable] stay internal and free to change. A provider that throws contributes nothing for that
 * class rather than breaking member enumeration, which every completion and resolution passes through.
 */
class PublishedGeneratedMembers(private val provider: KotlinGeneratedMemberProvider) : KotlinSyntheticMemberProvider {

    override fun staticMembers(cls: RawClass, ctx: KotlinSyntheticMemberProvider.Context): List<RawCallable> =
        runCatching { provider.staticMembers(View(cls), Ctx(ctx)) }.getOrDefault(emptyList()).map { it.toRaw(cls) }

    override fun instanceMembers(cls: RawClass, ctx: KotlinSyntheticMemberProvider.Context): List<RawCallable> =
        runCatching { provider.instanceMembers(View(cls), Ctx(ctx)) }.getOrDefault(emptyList()).map { it.toRaw(cls) }

    private fun KotlinGeneratedMember.toRaw(cls: RawClass) = RawCallable(
        name = name,
        isFunction = isFunction,
        receiverText = null,
        returnText = type,
        initializerText = null,
        paramTexts = parameters.map { (n, t) -> n to t },
        ctx = cls.ctx,
        node = cls.node, // navigate to the class: a generated member has no declaration of its own
        isVar = isMutable,
    )

    private class Ctx(private val inner: KotlinSyntheticMemberProvider.Context) : KotlinGeneratedMemberContext {
        override fun hasType(fqName: String): Boolean = inner.hasType(fqName)
    }

    private class View(private val cls: RawClass) : KotlinSourceClass {
        override val fqName get() = cls.fqn
        override val simpleName get() = cls.simpleName
        override val annotations get() = cls.annotationNames
        override val annotationFqNames get() = cls.annotationFqns
        override val supertypes get() = cls.superTypeTexts
        override val typeParameters get() = cls.typeParameterNames
        override val isObject get() = cls.isObject
        override val isCompanion get() = cls.isCompanion
        override val isInterface get() = cls.isInterface
        override val isEnum get() = cls.isEnum
        override val properties get() = cls.members.filter { !it.isFunction }.map { it.name to it.returnText }
        override val constructorParameters get() = cls.constructors.firstOrNull()?.paramTexts.orEmpty()
    }
}
