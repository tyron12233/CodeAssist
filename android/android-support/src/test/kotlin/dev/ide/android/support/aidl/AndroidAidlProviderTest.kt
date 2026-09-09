package dev.ide.android.support.aidl

import dev.ide.android.support.AndroidAidlProvider
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidSupport
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticTypeKind
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.Module
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.Workspace
import dev.ide.model.impl.ProjectModel
import dev.ide.testkit.testEnv
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor-facing half of AIDL support: the light classes an `.aidl` file will generate, contributed
 * before any build has run so a `Service` extending `IFoo.Stub` resolves as soon as it is typed.
 *
 * The shapes asserted here are the ones [AidlJavaGenerator] emits, which is the property that matters:
 * a completion list that changes the moment a build finishes would be worse than none.
 */
class AndroidAidlProviderTest {

    @Test
    fun `surfaces the interface with its Stub and Default`() = withApp { module, workspace ->
        writeAidl(
            module, "com/example/app/IGreeter.aidl",
            """
            package com.example.app;

            /** Greets people. */
            interface IGreeter {
                const int VERSION = 2;
                String greet(String name);
                oneway void ping();
            }
            """.trimIndent(),
        )
        val classes = classesFor(module, workspace)
        val iface = classes.single { it.fqName == "com.example.app.IGreeter" }

        assertEquals(SyntheticTypeKind.INTERFACE, iface.kind)
        assertTrue("android.os.IInterface" in iface.interfaces)
        assertEquals("Greets people.", iface.doc)
        assertEquals("java.lang.String", iface.methods.single { it.name == "greet" }.returnType)
        assertEquals(listOf("java.lang.String"), iface.methods.single { it.name == "greet" }.parameters.map { it.type })
        assertEquals("2", iface.fields.single { it.name == "VERSION" }.constant)

        val stub = iface.nestedClasses.single { it.fqName == "com.example.app.IGreeter.Stub" }
        assertEquals("android.os.Binder", stub.superClass)
        assertTrue(SyntheticModifier.ABSTRACT in stub.modifiers)
        // A service subclasses Stub and must see the interface's own methods on it, plus the entry points.
        assertTrue(stub.methods.map { it.name }.containsAll(listOf("greet", "ping", "asInterface", "asBinder", "setDefaultImpl")))
        // The interface's methods are abstract on the Stub, so a service that forgets one is flagged.
        assertTrue(SyntheticModifier.ABSTRACT in stub.methods.single { it.name == "greet" }.modifiers)
        assertTrue(SyntheticModifier.ABSTRACT !in stub.methods.single { it.name == "asInterface" }.modifiers)
        assertEquals("\"com.example.app.IGreeter\"", stub.fields.single { it.name == "DESCRIPTOR" }.constant)

        val default = iface.nestedClasses.single { it.fqName == "com.example.app.IGreeter.Default" }
        assertTrue("com.example.app.IGreeter" in default.interfaces)
    }

    /** A structured parcelable is a real generated class, so its fields and `CREATOR` must complete. */
    @Test
    fun `surfaces a structured parcelable with its fields and CREATOR`() = withApp { module, workspace ->
        writeAidl(
            module, "com/example/app/Config.aidl",
            "package com.example.app;\nparcelable Config {\n  int width;\n  String label;\n}\n",
        )
        val config = classesFor(module, workspace).single { it.fqName == "com.example.app.Config" }
        assertTrue("android.os.Parcelable" in config.interfaces)
        assertEquals("int", config.fields.single { it.name == "width" }.type)
        assertEquals("java.lang.String", config.fields.single { it.name == "label" }.type)
        assertEquals("android.os.Parcelable.Creator<com.example.app.Config>", config.fields.single { it.name == "CREATOR" }.type)
        assertTrue(config.methods.map { it.name }.containsAll(listOf("writeToParcel", "readFromParcel", "describeContents")))
    }

    /**
     * `parcelable Foo;` names a class the developer wrote by hand. Contributing a synthetic `Foo` for it
     * would shadow the real one, so the provider must stay out of the way.
     */
    @Test
    fun `contributes nothing for a forward-declared parcelable`() = withApp { module, workspace ->
        writeAidl(module, "com/example/app/Point.aidl", "package com.example.app;\nparcelable Point;\n")
        assertTrue(classesFor(module, workspace).isEmpty())
    }

    /** An enum's values are its backing type, since the Java backend generates constants, not a Java enum. */
    @Test
    fun `surfaces an enum as backing-typed constants`() = withApp { module, workspace ->
        writeAidl(
            module, "com/example/app/Level.aidl",
            "package com.example.app;\n@Backing(type=\"int\")\nenum Level { LOW, HIGH }\n",
        )
        val level = classesFor(module, workspace).single { it.fqName == "com.example.app.Level" }
        assertEquals(SyntheticTypeKind.ANNOTATION, level.kind)
        assertEquals(listOf("int", "int"), level.fields.map { it.type })
    }

    /** A module with no `.aidl` must cost nothing and contribute nothing. */
    @Test
    fun `emits nothing without any aidl files`() = withApp { module, workspace ->
        assertTrue(classesFor(module, workspace).isEmpty())
    }

    /** A malformed file must not take the rest of the module's declarations down with it. */
    @Test
    fun `a broken file does not suppress its neighbours`() = withApp { module, workspace ->
        writeAidl(module, "com/example/app/IBroken.aidl", "package com.example.app;\ninterface IBroken {\n  int f(\n")
        writeAidl(module, "com/example/app/IFine.aidl", "package com.example.app;\ninterface IFine { void f(); }\n")
        assertEquals(listOf("com.example.app.IFine"), classesFor(module, workspace).map { it.fqName })
    }

    // ---------------------------------------------------------------- fixtures

    private fun withApp(block: (Module, Workspace) -> Unit) = testEnv("aidl-provider") { env ->
        val moduleTypes = ModuleTypeRegistry(env.platform.extensions)
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(moduleTypes, codecs)
        val store = ProjectModel.open(env.dir, env.platform, codecs)
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", moduleTypes.resolve("android-app")).apply {
                putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34))
            }
            commit()
        }
        block(store.workspace.projects.single().modules.first { it.name == "app" }, store.workspace)
    }

    private fun writeAidl(module: Module, relativePath: String, source: String) {
        val root = Paths.get(
            module.sourceSets.flatMap { it.contentRoots }.first { ContentRole.AIDL in it.roles }.dir.path
        )
        val target = root.resolve(relativePath)
        Files.createDirectories(target.parent)
        target.writeText(source)
    }

    private fun classesFor(module: Module, workspace: Workspace): List<SyntheticClass> {
        val ctx = object : SyntheticClassContext {
            override val module = module
            override val workspace = workspace
        }
        return AndroidAidlProvider().classesFor(ctx)
    }
}
