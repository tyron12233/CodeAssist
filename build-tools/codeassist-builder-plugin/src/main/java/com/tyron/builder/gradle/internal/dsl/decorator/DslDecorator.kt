package com.tyron.builder.gradle.internal.dsl.decorator

import com.android.utils.usLocaleCapitalize
import com.android.utils.usLocaleDecapitalize
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.tyron.builder.gradle.internal.dsl.AgpDslLockedException
import com.tyron.builder.gradle.internal.dsl.Lockable
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.NonNullableSetter
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.internal.classloader.ClassLoaderUtils
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.*
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * A generator of part of the implementation of the AGP DSL
 *
 * Given an abstract class, calling [decorate] will return a generated subclass which has
 * anything that was abstract and is included in the [supportedPropertyTypes] implemented.
 */
class DslDecorator(supportedPropertyTypes: List<SupportedPropertyType>) {

    private val supportedPropertyTypes: Map<Type, SupportedPropertyType> = supportedPropertyTypes.associateBy { it.type }

    private val cache: LoadingCache<Class<*>, Class<*>> =
        CacheBuilder.newBuilder().build(object : CacheLoader<Class<*>, Class<*>>() {
            override fun load(dslClass: Class<*>): Class<*> {
                val stopWatch = Stopwatch.createStarted()
                try {
                    return decorateDslClassImpl(dslClass)
                } finally {
                    val logger = Logging.getLogger(DslDecorator::class.java)
                    if (logger.isDebugEnabled) {
                        logger.debug("Class {} instrumented in {}", dslClass, stopWatch.elapsed())
                    }
                }
            }
        })

    fun <T : Any> decorate(dslClass: KClass<T>): Class<out T> = decorate(dslClass.java)

    fun <T : Any> decorate(dslClass: Class<T>): Class<out T> {
        @Suppress("UNCHECKED_CAST") // This is safe as the generator generates a subclass
        return cache.get(dslClass) as Class<out T>
    }

    internal fun <T : Any> decorateDslClassImpl(dslClass: Class<T>): Class<out T> {

        val dslClassType = Type.getType(dslClass)
        val generatedClass =
            Type.getType(dslClassType.descriptor.removeSuffix(";") + "\$AgpDecorated;")

        try {
            @Suppress("UNCHECKED_CAST")
            return dslClass.classLoader.loadClass(generatedClass.className) as Class<out T>
        } catch (ignored: ClassNotFoundException) {
            // Define the class
        }

        val classWriter =
            ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)

        val isInterface = dslClass.isInterface
        val generatedClassSuperClass = if (isInterface) OBJECT_TYPE else dslClassType

        val interfaces = when {
            isInterface -> arrayOf(dslClassType.internalName, LOCKABLE_TYPE.internalName)
            else -> arrayOf(LOCKABLE_TYPE.internalName)
        }
        classWriter.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            generatedClass.internalName,
            null,
            generatedClassSuperClass.internalName,
            interfaces
        )

        val abstractProperties = findAbstractProperties(dslClass)

        val constructors = (if (isInterface) Any::class.java else dslClass).declaredConstructors
        for (constructor in constructors) {
            val method = Method.getMethod(constructor)
            val inject = constructor.getDeclaredAnnotation(Inject::class.java) != null
            val withLazyInit = constructor.getDeclaredAnnotation(WithLazyInitialization::class.java)
            if (method.argumentTypes.isNotEmpty() && !inject) {
                // Gradle only looks at constructors with arguments if they are marked with @Inject.
                continue
            }
            // Replace any zero-argument constructor with a single-argument constructor that
            // takes the DSL services type - to support generation from interfaces.
            val methodToGenerate =
                if (method.argumentTypes.isEmpty()) {
                    Method(method.name, method.returnType, arrayOf(DSL_SERVICES_TYPE))
                } else {
                    method
                }
            val dslServicesArgumentIndex = methodToGenerate.argumentTypes.indexOf(DSL_SERVICES_TYPE)
            check(dslServicesArgumentIndex != -1) {
                "Cannot generate implementation for $dslClassType" +
                        "@Inject marked constructor $method for does not include $DSL_SERVICES_TYPE argument"
            }
            GeneratorAdapter(
                constructor.modifiers,
                methodToGenerate,
                null,
                null,
                classWriter
            ).apply {
                // Always mark the generated constructor with @Inject
                visitAnnotation(INJECT_TYPE, true).visitEnd()
                // super(...args...)
                visitCode()
                loadThis()
                loadArgs(0, method.argumentTypes.size)
                invokeConstructor(generatedClassSuperClass, method)
                for (property in abstractProperties) {
                    when (val type = property.supportedPropertyType) {
                        is SupportedPropertyType.Collection -> {
                            // field = new ImplType("propertyName")
                            loadThis()
                            newInstance(type.implementationType)
                            dup()
                            visitLdcInsn(property.name)
                            invokeConstructor(type.implementationType, LOCKABLE_CONSTRUCTOR)
                            putField(
                                generatedClass,
                                property.backingFieldName,
                                type.implementationType
                            )
                        }
                        is SupportedPropertyType.Var -> { /* Defaults to field default value */
                        }
                        is SupportedPropertyType.Block -> {
                            // field = dslServices.newDecoratedInstance(implType, arrayOf(dslServices))
                            loadThis()
                            loadArg(dslServicesArgumentIndex)
                            visitLdcInsn(type.implementationType)
                            visitArrayOf {
                                loadArg(dslServicesArgumentIndex)
                            }
                            invokeInterface(DSL_SERVICES_TYPE, DSL_SERVICES_NEW_DECORATED_INSTANCE)
                            checkCast(type.implementationType)
                            putField(
                                generatedClass,
                                property.backingFieldName,
                                type.implementationType
                            )
                        }
                    }
                }

                withLazyInit?.let {
                    loadThis()
                    invokeVirtual(
                        generatedClass,
                        Method(
                            it.methodName,
                            Type.VOID_TYPE,
                            arrayOf()
                        )
                    )
                }
                returnValue()
                endMethod()
            }
        }
        createLockField(classWriter)
        for (field in abstractProperties) {
            createField(classWriter, field)
        }

        for (field in abstractProperties) {
            createFieldBackedGetters(classWriter, generatedClass, field)
            createFieldBackedSetters(classWriter, generatedClass, field)
            createBlockAccessor(classWriter, generatedClass, field)
        }

        createLockMethod(classWriter, generatedClass, abstractProperties)

        classWriter.visitEnd()

        return defineClass(dslClass, generatedClass.className, classWriter.toByteArray())
    }

    private inline fun GeneratorAdapter.visitArrayOf(item: GeneratorAdapter.() -> Unit) {
        // array = new Object[1]
        visitInsn(Opcodes.ICONST_1)
        newArray(OBJECT_TYPE)
        dup()
        // array[0] = item
        visitInsn(Opcodes.ICONST_0)
        item()
        arrayStore(OBJECT_TYPE)
    }

    @VisibleForTesting
    internal interface ClassInfoVisitor {
        fun method(method: java.lang.reflect.Method)
    }

    class CollectingInfoVisitor: ClassInfoVisitor {
        val getters = mutableMapOf<String, MutableList<java.lang.reflect.Method>>()
        val setters = mutableMapOf<String, MutableList<java.lang.reflect.Method>>()
        val blocks = mutableMapOf<String, java.lang.reflect.Method>()

        private fun MutableMap<String, MutableList<java.lang.reflect.Method>>.recordMethod(
            name: String, method: java.lang.reflect.Method) {
            getOrPut(name.usLocaleDecapitalize()) { mutableListOf() }.add(method)
        }

        override fun method(method: java.lang.reflect.Method) {
            val modifiers = method.modifiers
            if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) return
            if (method.getAnnotation(Inject::class.java) != null) return
            when {
                method.name.startsWith("get") -> {
                    if (method.parameterCount != 0) return
                    getters.recordMethod(method.name.removePrefix("get"), method)
                }
                method.name.startsWith("is") && method.name.length > 2 &&
                        method.name[2].isUpperCase() -> {
                    if (method.parameterCount != 0) return
                    getters.recordMethod(method.name.removePrefix("is"), method)
                }
                method.name.startsWith("set") -> {
                    if (method.returnType != Void.TYPE) return
                    if (method.parameterCount != 1) return
                    setters.recordMethod(method.name.removePrefix("set"), method)
                }
                method.returnType === Void.TYPE
                        && method.parameterCount ==1
                        && Type.getType(method.parameterTypes[0]) == KOTLIN_FUNCTION1 -> {
                    blocks.putIfAbsent(method.name, method)
                }
            }
        }
    }


    internal fun visitClass(theClass: Class<*>, visitor: ClassInfoVisitor) {
        val queue = ArrayDeque<Class<*>>()
        var c = theClass
        // First visit all superclasses, then interfaces.
        while (c != Any::class.java) { // Ignore Object
            queue.add(c)
            c = c.superclass ?: break // Interfaces don't have superclasses.
        }
        val seen = mutableSetOf<Class<*>>()
        while (true) {
            val current = queue.pollFirst() ?: break
            if (!seen.add(current)) {
                continue
            }
            for (method in current.declaredMethods) {
                visitor.method(method)
            }
            queue.addAll(current.interfaces)
        }
    }

    private class ManagedProperty(
        val name: String,
        val backingFieldName: String,
        val supportedPropertyType: SupportedPropertyType,
        val access: Int,
        val gettersToGenerate: Collection<Method>,
        val settersToGenerate: Collection<Method>,
        val blockAccessorToGenerate: Method?,
        val settersAnnotations: Collection<Annotation>,
    )

    private fun findAbstractProperties(dslClass: Class<*>): List<ManagedProperty> {

        val visitor = CollectingInfoVisitor()
        visitClass(dslClass, visitor)

        return visitor.getters.mapNotNull { (propertyName, getters) ->
            if (getters.any { !Modifier.isAbstract(it.modifiers) }) {
                // Implemented already
                return@mapNotNull null
            }

            val getterReturnTypes: MutableSet<Type> = mutableSetOf()
            var supportedPropertyType: SupportedPropertyType? = null
            var modifiers: Int = 0

            for (getter in getters) {
                val returnType = Type.getReturnType(getter)
                getterReturnTypes += returnType
                val getterSupportedPropertyType = supportedPropertyTypes[returnType] ?: continue
                if (supportedPropertyType == null) {
                    supportedPropertyType = getterSupportedPropertyType
                    // Take the modifiers from the first getter found,
                    // as it might be public overriding a protected getter in a superclass.
                    modifiers = notAbstract(getter.modifiers)
                }
                // And check that all the types are consistent.
                if (getterSupportedPropertyType != supportedPropertyType) {
                    throw IllegalStateException("Invalid abstract property '$propertyName' - ambiguous property mapping to multiple supported property types - $getterSupportedPropertyType & $supportedPropertyType")
                }
            }

            if (supportedPropertyType == null) {
                // Not a supported property type
                return@mapNotNull null
            }

            val setters = visitor.setters[propertyName]?.filter { Modifier.isAbstract(it.modifiers) } ?: listOf()
            val blockToGenerate =  visitor.blocks[propertyName]?.also {
                if (supportedPropertyType !is SupportedPropertyType.Block) {
                    throw IllegalStateException("Invalid block method $it for non block property $propertyName of type $supportedPropertyType")
                }
            }

            ManagedProperty(
                propertyName,
                "__$propertyName",
                supportedPropertyType,
                modifiers,
                getters.asSequence().map { Method.getMethod(it) }.toSet(),
                setters.asSequence().map { Method.getMethod(it) }.toSet(),
                blockToGenerate?.let { Method.getMethod(it) },
                setters.flatMap { it.annotations.toList() }.toSet()
            )
        }
    }

    private fun <T> defineClass(
        originalClass: Class<T>,
        name: String,
        bytes: ByteArray
    ): Class<out T> {
        return ClassLoaderUtils.define(originalClass.classLoader, name, bytes)
//        return if (JavaVersion.current().isJava9Compatible) {
//            lookupDefineClass(originalClass, bytes)
//        } else {
//            legacyDefineClass(originalClass, bytes)
//        }
    }

    private fun <T> lookupDefineClass(originalClass: Class<T>, bytes: ByteArray): Class<out T> {
        val lookup =
            privateLookupInMethod.invoke(
                null,
                originalClass,
                MethodHandles.lookup()
            ) as MethodHandles.Lookup
        try {
            @Suppress("UNCHECKED_CAST") return lookupDefineClassMethod.invoke(
                lookup,
                bytes
            ) as Class<out T>
        } catch (e: InvocationTargetException) {
            throw RuntimeException(
                "Internal error happened generating implementation for " +
                        originalClass +
                        ".\n" +
                        "This is usually caused by having different " +
                        "classloaders for different AGP jars. If you have an api dependency " +
                        "on `com.android.tools.build:gradle:gradle-api` in your buildSrc, try " +
                        "changing the dependency to be compileOnly or adding a runtime " +
                        "dependency on `com.android.tools.build:gradle:gradle` in your buildSrc.",
                e
            )
        }
    }

    // Define the class on JDKs before 9. AGP 7.0 Doesn't support running on JDKs before 11, but
    // we want configuration to work with JDK 8 so we can provide a helpful error message.
    private fun <T> legacyDefineClass(
        originalClass: Class<T>,
        bytes: ByteArray
    ): Class<out T> {
        @Suppress("UNCHECKED_CAST")
        return classLoaderDefineClass.invoke(
            originalClass.classLoader, null, bytes, 0, bytes.size
        ) as Class<T>
    }

    private fun createLockField(classWriter: ClassWriter) {
        classWriter.visitField(
            Opcodes.ACC_PRIVATE,
            LOCK_FIELD_NAME,
            Type.BOOLEAN_TYPE.descriptor,
            "",
            false
        ).visitEnd()
    }

    private fun createLockMethod(
        classWriter: ClassWriter,
        generatedClass: Type,
        abstractProperties: List<ManagedProperty>
    ) {
        GeneratorAdapter(Modifier.PUBLIC or Modifier.FINAL, Method.getMethod("void lock()"), null, null, classWriter).apply {
            // this.__lock = true;
            loadThis()
            push(true)
            putField(generatedClass, LOCK_FIELD_NAME, Type.BOOLEAN_TYPE)
            for (abstractProperty in abstractProperties) {
                val type = abstractProperty.supportedPropertyType
                if (type is SupportedPropertyType.Collection || type is SupportedPropertyType.Block) {
                    // this.__managedField.lock();
                    loadThis()
                    getField(generatedClass, abstractProperty.backingFieldName, type.implementationType)
                    checkCast(LOCKABLE_TYPE)
                    invokeInterface(LOCKABLE_TYPE, LOCK_METHOD)
                }
            }
            returnValue()
            endMethod()
        }
    }

    private fun createField(
        classWriter: ClassWriter,
        managedProperty: ManagedProperty,
    ) {
        classWriter.visitField(
            Opcodes.ACC_PRIVATE,
            managedProperty.backingFieldName,
            managedProperty.supportedPropertyType.implementationType.descriptor,
            "",
            null
        ).visitEnd()
    }

    private fun createFieldBackedSetters(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty
    ) {
        when (property.supportedPropertyType) {
            is SupportedPropertyType.Collection -> {
                createGroovyMutatingSetter(classWriter, generatedClass, property)
            }
            is SupportedPropertyType.Var -> {
                for (setter in property.settersToGenerate) {
                    createFieldBackedSetter(
                        classWriter,
                        generatedClass,
                        property,
                        setter,
                        disallowNullableValue = property.settersAnnotations.any {
                            it.annotationClass == NonNullableSetter::class
                        }
                    )
                }
            }
        }
    }

    private fun createFieldBackedSetter(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty,
        setter: Method,
        disallowNullableValue: Boolean
    ) {
        val type = property.supportedPropertyType
        check(type.implementationType == setter.argumentTypes[0]) {
            "Currently only setters that use the same type are supported."
        }
        // Mark bridge methods as synthetic.
        val access = if(type.type == setter.argumentTypes[0]) property.access else property.access.or(Opcodes.ACC_SYNTHETIC)
        GeneratorAdapter(access, setter, null, null, classWriter).apply {
            loadThis()
            // if (this.__locked__) { throw new AgpDslLockedExtension("...") }
            newLabel().also { actuallySet ->
                getField(generatedClass, LOCK_FIELD_NAME, Type.BOOLEAN_TYPE)
                visitJumpInsn(Opcodes.IFEQ, actuallySet)
                // TODO: Share the base string between methods/classes?
                // TODO: URL
                throwException(
                    LOCKED_EXCEPTION,
                    "It is too late to set ${property.name.removePrefix("_")}\n" +
                            "It has already been read to configure this project.\n" +
                            "Consider either moving this call to be during evaluation,\n" +
                            "or using the variant API."
                )
                visitLabel(actuallySet)
            }
            // this.__managedField = argument;
            loadThis()
            loadArg(0)
            // this.__managedField = Preconditions.checkNotNull(argument);
            if (disallowNullableValue) {
                invokeStatic(PRECONDITIONS, PRECONDITIONS_CHECK_NOT_NULL)
                checkCast(type.implementationType)
            }
            putField(generatedClass, property.backingFieldName, type.implementationType)
            returnValue()
            endMethod()
        }
    }

    /**
     * To allow groovy field += bar, we need to generate a set(...) method.
     *
     * Mutability is not controlled here, it depends on the underlying lockable collection type.
     */
    private fun createGroovyMutatingSetter(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty
    ) {
        val extraSetter = Method(
            "set${property.name.usLocaleCapitalize()}",
            Type.VOID_TYPE,
            arrayOf(property.supportedPropertyType.type)
        )
        if (property.supportedPropertyType == SupportedPropertyType.Collection.Map) {
            GeneratorAdapter(
                Opcodes.ACC_PUBLIC.or(Opcodes.ACC_SYNTHETIC),
                extraSetter,
                null,
                null,
                classWriter
            ).apply {
                // val newMap = HashMap(argument) // Take a copy so e.g. field = field doesn't clear the field!
                // __backingField.clear()
                // __backingField.putAll(newMap)
                newInstance(HASH_MAP)
                dup()
                loadArg(0)
                checkCast(MAP)
                invokeConstructor(
                    HASH_MAP,
                    HASH_MAP_COPY_CONSTRUCTOR
                )
                loadThis()
                getField(
                    generatedClass, property.backingFieldName,
                    property.supportedPropertyType.implementationType
                )
                dup()
                invokeVirtual(
                    property.supportedPropertyType.implementationType,
                    CLEAR
                )
                swap()
                invokeVirtual(
                    property.supportedPropertyType.implementationType,
                    PUT_ALL
                )
                returnValue()
                endMethod()
            }
        } else {
            GeneratorAdapter(
                Opcodes.ACC_PUBLIC.or(Opcodes.ACC_SYNTHETIC),
                extraSetter,
                null,
                null,
                classWriter
            ).apply {
                // val newList = ArrayList(argument) // Take a copy so e.g. field = field doesn't clear the field!
                // __backingField.clear()
                // __backingField.addAll(newList)
                newInstance(ARRAY_LIST)
                dup()
                loadArg(0)
                checkCast(COLLECTION)
                invokeConstructor(
                    ARRAY_LIST,
                    ARRAY_LIST_COPY_CONSTRUCTOR
                )
                loadThis()
                getField(
                    generatedClass, property.backingFieldName,
                    property.supportedPropertyType.implementationType
                )
                dup()
                invokeVirtual(
                    property.supportedPropertyType.implementationType,
                    CLEAR
                )
                swap()
                invokeVirtual(
                    property.supportedPropertyType.implementationType,
                    ADD_ALL
                )
                returnValue()
                endMethod()
            }
        }
    }

    private fun createFieldBackedGetters(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty,
    ) {
        for (getter in property.gettersToGenerate) {
            createFieldBackedGetter(classWriter, generatedClass, property, getter)
        }
    }
    private fun createFieldBackedGetter(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty,
        getter: Method,
    ) {
        val type = property.supportedPropertyType
        // Mark bridge methods as synthetic.
        val access = when(getter.returnType) {
            type.type, type.implementationType -> property.access
            else -> property.access.or(Opcodes.ACC_SYNTHETIC)
        }

        GeneratorAdapter(access, getter, null, null, classWriter).apply {
            loadThis()
            getField(generatedClass, property.backingFieldName, type.implementationType)
            if (type.implementationType != getter.returnType) {
                checkCast(getter.returnType)
            }
            returnValue()
            endMethod()
        }
    }

    private fun createBlockAccessor(
        classWriter: ClassWriter,
        generatedClass: Type,
        property: ManagedProperty
    ) {
        if (property.supportedPropertyType !is SupportedPropertyType.Block) return
        val type = property.supportedPropertyType
        val access = Opcodes.ACC_PUBLIC.or(Opcodes.ACC_FINAL)
        val method = property.blockAccessorToGenerate ?: return
        // Implement the expected kotlin 'block' method
        // ```
        // fun blockName(action: BlockType.()->Unit) {
        //
        // }
        // ```
        GeneratorAdapter(access, method, null, null, classWriter).apply {
            loadArg(0)
            loadThis()
            getField(generatedClass, property.backingFieldName, type.implementationType)
            checkCast(type.type)
            invokeInterface(KOTLIN_FUNCTION1, KOTLIN_FUNCTION1_INVOKE)
            pop()
            returnValue()
            endMethod()
        }
        // And for groovy, generate the gradle Action<> taking method, which Gradle will then
        // generate a groovy closure method for:
        val gradleActionMethod = Method(method.name, Type.VOID_TYPE, arrayOf(GRADLE_ACTION))
        GeneratorAdapter(access, gradleActionMethod, null, null, classWriter).apply {
            loadArg(0)
            loadThis()
            getField(generatedClass, property.backingFieldName, type.implementationType)
            checkCast(type.type)
            invokeInterface(GRADLE_ACTION, GRADLE_ACTION_EXECUTE)
            returnValue()
            endMethod()
        }
    }

    companion object {

        private const val LOCK_FIELD_NAME = "__locked__"

        private fun notAbstract(modifiers: Int): Int = modifiers and Modifier.ABSTRACT.inv()
        private val OBJECT_TYPE = Type.getType(Any::class.java)
        private val INJECT_TYPE = Type.getDescriptor(Inject::class.java)
        private val LOCKABLE_TYPE = Type.getType(Lockable::class.java)
        private val LOCKABLE_CONSTRUCTOR =
            Method("<init>", Type.VOID_TYPE, arrayOf(Type.getType(String::class.java)))
        private val LOCK_METHOD = Method("lock", Type.VOID_TYPE, arrayOf())
        private val LOCKED_EXCEPTION = Type.getType(AgpDslLockedException::class.java)
        private val DSL_SERVICES_TYPE = Type.getType(DslServices::class.java)
        private val ARRAY = Type.getType(Array::class.java)
        private val CLASS = Type.getType(Class::class.java)
        //    fun <T: Any> newDecoratedInstance(dslClass: Class<T>, vararg args: Any) : T
        private val DSL_SERVICES_NEW_DECORATED_INSTANCE =
            Method("newDecoratedInstance", OBJECT_TYPE, arrayOf(CLASS, ARRAY))

        private val COLLECTION = Type.getType(Collection::class.java)
        private val ARRAY_LIST = Type.getType(ArrayList::class.java)
        private val MAP = Type.getType(Map::class.java)
        private val HASH_MAP = Type.getType(HashMap::class.java)
        private val ARRAY_LIST_COPY_CONSTRUCTOR = Method("<init>", Type.VOID_TYPE, arrayOf(COLLECTION))
        private val HASH_MAP_COPY_CONSTRUCTOR = Method("<init>", Type.VOID_TYPE, arrayOf(MAP))
        private val CLEAR = Method("clear", Type.VOID_TYPE, arrayOf())
        private val ADD_ALL = Method("addAll", Type.BOOLEAN_TYPE, arrayOf(COLLECTION))
        private val PUT_ALL = Method("putAll", Type.VOID_TYPE, arrayOf(MAP))
        private val KOTLIN_FUNCTION1 = Type.getType("Lkotlin/jvm/functions/Function1;")

        private val KOTLIN_FUNCTION1_INVOKE = Method("invoke", OBJECT_TYPE, arrayOf(OBJECT_TYPE))
        private val GRADLE_ACTION = Type.getType(Action::class.java)
        private val GRADLE_ACTION_EXECUTE = Method("execute", Type.VOID_TYPE, arrayOf(OBJECT_TYPE))

        private val PRECONDITIONS = Type.getType(Preconditions::class.java)
        private val PRECONDITIONS_CHECK_NOT_NULL = Method("checkNotNull", OBJECT_TYPE,
            arrayOf(OBJECT_TYPE))

        // Use reflection to avoid needing to compile against java11 APIs yet.
        private val privateLookupInMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
            MethodHandles::class.java.getDeclaredMethod(
                "privateLookupIn",
                Class::class.java,
                MethodHandles.Lookup::class.java
            )
        }
        private val lookupDefineClassMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
            MethodHandles.Lookup::class.java.getDeclaredMethod("defineClass", ByteArray::class.java)
        }
        // And for Java 8 support, so AGP can give a sync error rather than throwing.
        private val classLoaderDefineClass by lazy(LazyThreadSafetyMode.PUBLICATION) {
            ClassLoader::class.java.getDeclaredMethod(
                "defineClass",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { it.isAccessible = true }
        }
    }

}
