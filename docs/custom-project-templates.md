# Writing custom project templates

The Create-Project gallery is contributed, not hard-coded. Every entry in it, including the Android app, the
Java console app and the plugin scaffold, is a `ProjectTemplate` on the `platform.projectTemplate` extension
point, registered the same way a plugin registers one. A template you add is not a second-class citizen: it
appears in the same gallery, collects its inputs through the same data-driven form, authors the project
through the same model transaction, and gets its dependencies resolved by the same Maven resolver.

A template is two things in one object: a **declaration** of the inputs it needs, so the Create-Project
screen can render a form without knowing anything about your project kind, and a **generator** that writes
the model and the files once those inputs are collected.

It assumes you have read [writing-plugins.md](writing-plugins.md), because the template is contributed
through the plugin model described there. [architecture.md](architecture.md) is the reference for the project
model a template authors into, and [custom-build-plugins.md](custom-build-plugins.md) covers the build side if
your project kind also needs its own build steps.

**Contents**

1. [Before you begin](#1-before-you-begin)
2. [What the host does around you](#2-what-the-host-does-around-you)
3. [Step 1: declare the template](#3-step-1-declare-the-template)
4. [Step 2: collect inputs](#4-step-2-collect-inputs)
5. [Step 3: author the model](#5-step-3-author-the-model)
6. [Step 4: write the files](#6-step-4-write-the-files)
7. [Step 5: declare dependencies](#7-step-5-declare-dependencies)
8. [Step 6: register it](#8-step-6-register-it)
9. [Worked example: a LibGDX game](#9-worked-example-a-libgdx-game)
10. [Test it](#10-test-it)
11. [Mistakes worth not making](#11-mistakes-worth-not-making)
12. [Checklist](#12-checklist)
13. [Appendix A: the API](#appendix-a-the-api)

---

## 1. Before you begin

Everything a template touches is in `project-model-api`, which is published, so a template can live in an
installed plugin with no access to the IDE's internals:

```kotlin
compileOnly(platform("io.github.tyron12233:plugin-bom:2.7.0"))
compileOnly("io.github.tyron12233:plugin-api")
compileOnly("io.github.tyron12233:project-model-api")
compileOnly("io.github.tyron12233:platform-core")
```

A template is contributed by an **engine facet** (`dev.ide.plugin.Plugin`), not a UI facet. It renders no
Compose: the gallery draws the form from your parameter declarations and the IDE writes the files.

Declare `PluginCapabilities.MODEL_MODULE_TYPE` if your template comes with a module type of its own. A
template that scaffolds a module type someone else already ships needs no capability for the template itself,
but does need `FS_WRITE`, since generating a project writes files.

## 2. What the host does around you

Knowing the sequence matters, because three of the four steps are not yours:

1. The gallery lists every registered template, grouped by `category`, and renders one control per
   `TemplateParameter` you declared plus the always-present name and package fields.
2. The host creates the workspace directory, seeds the platform SDK, and picks the language level (`JAVA_17`
   on desktop, `JAVA_8` on device, against a non-modular `android.jar`).
3. **Your `generate` runs**, synchronously, against a `ProjectScaffold` whose `rootDir` already exists and is
   empty. Then the host saves the model.
4. Your `dependencies(args)` are **declared** into `module.toml` and resolution is deferred to the background
   once the project opens. Creation therefore returns immediately, and a slow or offline resolve cannot lose
   a declaration: it stays in `module.toml` and the next open retries it.

`generate` is not suspending and must not block. Reach for `dependencies` rather than downloading anything
yourself.

## 3. Step 1: declare the template

```kotlin
object LibGdxGameTemplate : ProjectTemplate {
    override val id = TemplateId("libgdx-game")
    override val displayName = "LibGDX Game"
    override val description = "A LibGDX game that runs on Android, with a sprite and a render loop."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) { /* step 3 */ }
}
```

- **`id` is the identity.** It is what the host resolves when the user picks your row, so keep it stable and
  namespace it against the built-ins (`libgdx-game`, not `android-app`). Two templates with the same id is a
  collision the gallery cannot resolve for the user.
- **`category`** buckets the row: `ANDROID`, `JAVA`, `KOTLIN`, `PLUGIN`, or `OTHER`. Use `OTHER` rather than
  forcing a fit; it is there for exactly that.
- **`iconId`** is an id in the IDE's own icon registry (`module.android`, `java`, `pkg`), not a drawable. A
  plugin has no `Context` of its own, so it cannot ship one.

## 4. Step 2: collect inputs

`parameters()` is declarative so the screen stays data-driven. The project **name** and **package** are
always collected and are not yours to declare; add only what is specific to your project kind.

```kotlin
override fun parameters(): List<TemplateParameter> = listOf(
    TemplateParameter.Choice(
        key = "minSdk",
        label = "Minimum SDK",
        options = listOf(
            TemplateParameter.Choice.Option("26", "API 26 · Android 8.0"),
            TemplateParameter.Choice.Option("21", "API 21 · Android 5.0"),
        ),
        defaultIndex = 0,
        help = "Lowest Android version the game supports.",
    ),
    TemplateParameter.Toggle(
        key = "box2d",
        label = "Include Box2D physics",
        default = false,
    ),
    TemplateParameter.Text(
        key = "mainClass",
        label = "Game class",
        default = "MyGame",
        validation = TextValidation.IDENTIFIER,
        help = "Name of the generated ApplicationListener.",
    ),
)
```

Three control kinds, and one validation hint the screen applies client-side:

| Control | Renders as |
| --- | --- |
| `TemplateParameter.Text` | A text field, optionally validated as `IDENTIFIER`, `PACKAGE_NAME` or `PROJECT_NAME` |
| `TemplateParameter.Choice` | Segmented chips or a dropdown over `options`, defaulting to `defaultIndex` |
| `TemplateParameter.Toggle` | An on/off switch |

Read them back through `TemplateArgs`, always with a default, because a value can be absent or blank:

```kotlin
val minSdk = args.int("minSdk", 26)
val box2d = args.bool("box2d", false)
val gameClass = args.string("mainClass", "MyGame")
val projectName = args.name           // reserved: the project name
val pkg = args.packageName            // reserved: the base package / Android namespace
```

A single-option `Choice` is a control the user cannot change. If a value is fixed, hard-code it rather than
rendering a chip that does nothing.

## 5. Step 3: author the model

Two transactions: one on the workspace to add the project, one on the project to add its modules. Each has to
be committed.

```kotlin
override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
    scaffold.workspace.beginModification().apply {
        addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
        commit()
    }
    scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
        addModule("app", scaffold.moduleType("android-app")).apply {
            languageLevel = scaffold.languageLevel
        }
        commit()
    }
}
```

**Resolve a module type by id, do not ship your own copy of one.** `scaffold.moduleType(id)` returns the
registered type, and the module types the IDE ships are:

| Id | Produces |
| --- | --- |
| `java-lib` | A JVM module compiled by ecj |
| `android-app` | An APK |
| `android-lib` | An AAR |

A type's `defaultSourceSets()` lay down its conventional layout and its `defaultFacets()` attach starter
configuration, so a module added this way is already buildable. Use `scaffold.languageLevel` rather than
naming a level: the host chose it for the platform you are on.

If your project kind genuinely needs a layout none of those describes, contribute a `ModuleType` of your own
(see [custom-language-support.md](custom-language-support.md)) rather than bending one of these.

### Configuring a facet you do not own

A module type's starter facet is generic: the Android one arrives with `com.example.app` as its namespace and
the default SDK levels. Setting the real values means writing that facet, and the class that declares it
(`AndroidFacet`) belongs to `:android-support`, which is not published, so you cannot name it.

`putFacetData` is the route. You supply the `module.toml` table and the values; you never need the class:

```kotlin
addModule("app", scaffold.moduleType("android-app")).apply {
    languageLevel = scaffold.languageLevel
    putFacetData(
        FacetData(
            "android",                                  // the table AndroidFacetCodec persists to
            linkedMapOf(
                "namespace" to args.packageName,
                "compileSdk" to 36L,
                "minSdk" to args.int("minSdk", 26).toLong(),
                "targetSdk" to 36L,
                "isApplication" to true,
            ),
        ),
    )
}
```

The keys are the ones that plugin's codec reads, and the values must be TOML-representable (`String`,
`Boolean`, `Int`, `Long`, `Double`, and lists or string-keyed maps of those). Integers go out as `Long`, since
that is TOML's only integer type. `putFacetData` **replaces** the table, so anything you leave out falls back
to the codec's own default rather than to the module type's starter value; the Android codec defaults are
sane, but list `isApplication` explicitly, because an omitted one defaults to an app and would make an
`android-lib` module produce an APK.

Do **not** reach the same end by declaring a facet of your own and pointing its codec at another plugin's
table. See [Mistakes worth not making](#11-mistakes-worth-not-making).

## 6. Step 4: write the files

`ProjectScaffold` gives you two writers, both rooted at the workspace directory and both creating
intermediate directories:

```kotlin
scaffold.writeText("app/src/main/java/${pkg.replace('.', '/')}/MyGame.java", """
    package $pkg;

    public class MyGame {
    }
""")

scaffold.writeBytes("app/src/main/assets/logo.png", logoBytes)
```

- **`writeText` trims the common indentation** and appends a trailing newline, so a triple-quoted literal can
  stay indented at its call site. That trimming works on the *common* prefix, so interpolating a multi-line
  value into the middle of a block breaks it: the interpolated lines carry no indent, the common prefix
  becomes empty, and nothing is trimmed. Build multi-line pieces already indented to their final column, or
  write the literal flush-left.
- **`writeBytes` is byte-exact.** No charset round-trip, no trim, no appended newline. Use it for anything
  binary; `writeText` would corrupt it.

Binary assets you want to ship inside your plugin go in `src/main/resources` and come back out through the
classloader:

```kotlin
private fun resourceBytes(path: String): ByteArray =
    javaClass.getResourceAsStream(path)?.use { it.readBytes() }
        ?: error("Bundled resource not found: $path")
```

## 7. Step 5: declare dependencies

Do not resolve Maven coordinates yourself. Declare them, and the host attaches them to the named module:

```kotlin
override fun dependencies(args: TemplateArgs): List<TemplateDependency> = buildList {
    add(TemplateDependency("app", "com.badlogicgames.gdx:gdx:1.14.2"))
    add(TemplateDependency("app", "com.badlogicgames.gdx:gdx-backend-android:1.14.2"))
    if (args.bool("box2d", false)) {
        add(TemplateDependency("app", "com.badlogicgames.gdx:gdx-box2d:1.14.2"))
    }
}
```

`module` is the module **name** you passed to `addModule`, so the two have to agree; a coordinate declared
against a module the template never created is silently attached to nothing. `scope` defaults to
`implementation` and takes any scope the model knows (`api`, `compileOnly`, …).

`dependencies(args)` is called with the same args as `generate`, so a toggle can add one.

## 8. Step 6: register it

```kotlin
class LibGdxPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.example.libgdx",
        name = "LibGDX",
        description = "A Create-Project template for LibGDX games.",
        capabilities = listOf(PluginCapabilities.FS_WRITE),
        // The template resolves the `android-app` module type and the generated project is built by the
        // Android pipeline, so this plugin genuinely depends on that one.
        dependsOn = listOf("android-support"),
    )

    override fun register(reg: PluginRegistration) {
        reg.register(ProjectTemplateExtensionPoint, LibGdxGameTemplate)
    }
}
```

Registering through `PluginRegistration` is what attributes the template to your plugin and tracks it for
unload. `ProjectTemplateRegistry` wraps the same extension point and is what the built-ins use through their
existing facades; for new code the direct form is one line shorter and needs no `PluginId`.

**Declare `dependsOn`** for the plugin whose module type you resolve. It makes the load order a real edge, and
it means a user disabling that plugin disables yours rather than leaving your template to scaffold a module
type nothing claims.

## 9. Worked example: a LibGDX game

Everything above, as one file. The whole template is a single object with no state.

```kotlin
object LibGdxGameTemplate : ProjectTemplate {
    override val id = TemplateId("libgdx-game")
    override val displayName = "LibGDX Game"
    override val description = "A LibGDX game that runs on Android, with a sprite and a render loop."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    private const val MODULE = "app"
    private const val GDX = "1.14.2"

    override fun parameters() = listOf(
        TemplateParameter.Choice(
            key = "minSdk",
            label = "Minimum SDK",
            options = listOf(
                TemplateParameter.Choice.Option("26", "API 26 · Android 8.0"),
                TemplateParameter.Choice.Option("21", "API 21 · Android 5.0"),
            ),
            help = "Lowest Android version the game supports.",
        ),
    )

    override fun dependencies(args: TemplateArgs) = listOf(
        TemplateDependency(MODULE, "com.badlogicgames.gdx:gdx:$GDX"),
        TemplateDependency(MODULE, "com.badlogicgames.gdx:gdx-backend-android:$GDX"),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val path = pkg.replace('.', '/')

        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(MODULE, scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacetData(
                    FacetData(
                        "android",
                        linkedMapOf(
                            "namespace" to pkg,
                            "compileSdk" to 36L,
                            "minSdk" to args.int("minSdk", 26).toLong(),
                            "targetSdk" to 36L,
                            "isApplication" to true,
                        ),
                    ),
                )
            }
            commit()
        }

        scaffold.writeText("$MODULE/src/main/AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="@string/app_name" android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)

        scaffold.writeText("$MODULE/src/main/res/values/strings.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
            </resources>
        """)

        scaffold.writeText("$MODULE/src/main/java/$path/MainActivity.java", """
            package $pkg;

            import android.os.Bundle;
            import com.badlogic.gdx.backends.android.AndroidApplication;
            import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

            public class MainActivity extends AndroidApplication {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
                    config.useImmersiveMode = true;
                    initialize(new MyGame(), config);
                }
            }
        """)

        // Native libraries the runtime loads. Shipped in the plugin's own resources and copied out
        // byte-exact; writeText would corrupt them.
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            scaffold.writeBytes(
                "$MODULE/src/main/jniLibs/$abi/libgdx.so",
                resourceBytes("/natives/$abi/libgdx.so"),
            )
        }
    }

    private fun resourceBytes(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("Bundled resource not found: $path")
}
```

Two things about the natives are worth copying rather than the details of LibGDX. They are pinned to the same
version as the declared coordinate, so bumping one without the other would leave the Java half and the native
half disagreeing at runtime rather than at build time. And `x86_64` is included, because leaving it out means
the generated project cannot run on a standard emulator.

## 10. Test it

Templates are the easiest part of the IDE to test properly: create one exactly as the Create-Project flow
does, then build it. A typo in generated source is then a red test rather than a bug report.

```kotlin
class LibGdxTemplateTest {

    @Test
    fun theGameTemplateCompiles() {
        withTempDir("libgdx") { dir ->
            val env = ApplicationEnvironment()
            env.platform.extensions.register(
                ProjectTemplateExtensionPoint, LibGdxGameTemplate, PluginId("com.example.libgdx"),
            )
            IdeServices.createProjectAt(
                dir, "libgdx-game",
                mapOf(TemplateArgs.NAME to "Game", TemplateArgs.PACKAGE to "com.example.game"),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17, env = env,
            ).use { ide ->
                val capture = runBlocking { ide.runAndCapture("app") }
                assertTrue(capture.compiled, "diagnostics=${capture.diagnostics}")
            }
        }
    }
}
```

The built-in templates are covered this way (`SwingTemplatesTest`, `SampleProjectsTest`,
`ComposeSampleProjectsTest`), and it is worth asserting the model as well as the compile: that the facet you
wrote comes back (`module.facets.all`), and that the module name your `dependencies` name actually exists.

Write the test method with a **block body**. An expression-body test whose last expression is not `Unit` gets
a non-`Unit` return type, and JUnit Jupiter then skips it silently, with no error and a quietly lower test
count.

## 11. Mistakes worth not making

Every one of these has been seen in a real third-party template.

**Do not clone another plugin's facet class.** Copying `AndroidFacet` and `AndroidFacetCodec` into your own
package compiles, and its codec then claims the `android` table too. Table names are one flat namespace and
the last registration wins, and an installed plugin loads after the built-ins, so your copy quietly becomes
the codec for the `[android]` table in **every project on the device**, not just the ones your template
created. It works right up until the built-in codec gains a field yours does not have, at which point that
field is dropped from `module.toml` the next time anyone opens Module Settings. `putFacetData` exists so this
is never necessary.

**Do not re-register a module type you do not own.** Registering your own `android-app` puts a second "Android
Application" row in the New Module picker, and yours is unreachable anyway: types resolve first-registered-
wins and the built-ins load first. Resolve it with `scaffold.moduleType("android-app")` instead.

**Do not offer a choice with one option.** It renders a control the user cannot change. Hard-code the value.

**Do not name a module in `dependencies` that `generate` did not create.** Nothing errors; the coordinate is
attached to nothing and the generated project does not compile.

**Do not write the release keystore, secrets, or absolute paths into a generated project.** A template's
output is a project the user will commit.

**Do not assume your template ran.** `id` collisions and a disabled dependency both end with your template
absent from the gallery. The Plugins screen carries the reason a plugin did not load.

## 12. Checklist

- [ ] `id` is namespaced and stable, and does not collide with a built-in.
- [ ] `category` and `iconId` are set; the icon id is one the IDE's registry knows.
- [ ] Every parameter has a `help` string, and no parameter is a single-option choice.
- [ ] Every `args` read has a default.
- [ ] Both transactions are committed.
- [ ] Module types are resolved by id, not re-declared.
- [ ] Facets are written with `putFacetData`, values TOML-representable, integers as `Long`.
- [ ] `writeBytes` for anything binary.
- [ ] `dependencies` name a module `generate` actually creates.
- [ ] `dependsOn` names the plugin whose module type you resolve.
- [ ] `FS_WRITE` declared, plus `MODEL_MODULE_TYPE` if you ship a module type.
- [ ] A test creates the project through `createProjectAt` and compiles it, with a block-bodied test method.

## Appendix A: the API

Everything here is in `project-model-api`, package `dev.ide.model.template` unless noted.

| Type | What it is |
| --- | --- |
| `ProjectTemplate` | The template: identity, `parameters()`, `generate()`, `dependencies()` |
| `ProjectTemplateExtensionPoint` | `platform.projectTemplate`, where a template is contributed |
| `TemplateId` | The template's stable id |
| `TemplateCategory` | `ANDROID` / `JAVA` / `KOTLIN` / `PLUGIN` / `OTHER` |
| `TemplateParameter` | `Text` / `Choice` / `Toggle`, one control each |
| `TextValidation` | `NONE` / `IDENTIFIER` / `PACKAGE_NAME` / `PROJECT_NAME` |
| `TemplateArgs` | The collected values: `string`/`int`/`bool`, plus `name` and `packageName` |
| `ProjectScaffold` | `workspace`, `rootDir`, `languageLevel`, `moduleType(id)`, `writeText`, `writeBytes` |
| `TemplateDependency` | A Maven coordinate for a named module, with a scope |
| `dev.ide.model.ModifiableModule` | The module being authored: source sets, deps, `putFacetData` |
| `dev.ide.model.FacetData` | A facet as its `module.toml` table plus values |
| `dev.ide.model.ModuleTypeRegistry` | Resolves a module type id, if you need it outside a scaffold |
