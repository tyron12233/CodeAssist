package dev.ide.ios

import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.StoreScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.platform.ConcurrentMap
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.templates.JavaLibModuleType
import dev.ide.templates.BUILT_IN_KOTLIN_TEMPLATES
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam

/**
 * Project creation on iOS, through the real project model and the real templates.
 *
 * Before this the host scaffolded a folder: `src/Main.kt`, a hand-written starter string, and no model at
 * all. Everything downstream then had to guess — "one module named after the directory" was an invention,
 * and a project made here opened on the desktop as an unrecognised folder. The whole model
 * (`ProjectModelStore`, its transactions, `.platform/workspace.json` + `module.toml`) and the built-in
 * Kotlin templates now build for this target, so the honest thing is to use them: a project created on a
 * phone is the same project the desktop and Android hosts create, byte for byte.
 *
 * What it is NOT is a build. A template's job ends at the source tree and the model; compiling it is still
 * something only the JVM hosts do.
 */
internal class IosProjects {

    /**
     * The extension container the two registries read, and the one piece of state here.
     *
     * Per host, not per project: a module type and a template are contributed once, and the registries are
     * views over the extension registry rather than stores of their own.
     */
    private val platform = PlatformCore()

    private val moduleTypes = ModuleTypeRegistry(platform.extensions)
    private val templateRegistry = ProjectTemplateRegistry(platform.extensions)

    init {
        // The built-ins, registered the way `BuiltInPlugins` registers them on the JVM hosts. The Java,
        // Swing and Android templates are deliberately absent: this host has no Java editor and no Android
        // build, and offering a template whose language it cannot analyze is worse than offering nothing.
        moduleTypes.register(JavaLibModuleType, BUILT_IN)
        BUILT_IN_KOTLIN_TEMPLATES.forEach { templateRegistry.register(it, BUILT_IN) }
    }

    fun templates(): List<UiProjectTemplate> = templateRegistry.all().map(::toUi)

    /**
     * Scaffold [templateId] into [root], which must already exist and be empty.
     *
     * The store is opened, written through and CLOSED here rather than kept: the editor on this host reads
     * files, not the model, so holding a store open for the session would be a second source of truth for
     * a project that already has one on disk. [modules] reads it back when something needs it.
     */
    fun create(root: String, templateId: String, args: Map<String, String>): Result<Unit> = runCatching {
        val template = templateRegistry.byId(TemplateId(templateId))
            ?: error("No such template: $templateId")
        val store = ProjectModel.open(root, platform, FacetCodecRegistry())
        try {
            // JAVA_17 as on the desktop. Nothing here compiles against it — the level is what the module
            // records for whichever host DOES build the project later, and inventing a lower one because
            // this host cannot build would silently degrade the project on the host that can.
            template.generate(StoreScaffold(store, LanguageLevel.JAVA_17), TemplateArgs(args))
            store.save()
        } finally {
            store.close()
        }
    }

    /**
     * The modules recorded at [root], read straight off `.platform/workspace.json`.
     *
     * A JSON read rather than an opened store, because this answers a LIST screen: the picker asks it of
     * every project it shows, and opening a model (with its lock, bus and service container) per row to
     * count modules would be paying for the whole model to render a subtitle.
     *
     * Even a JSON read is too much per row per frame, so the answer is memoized against the model file's
     * modification time: a project list re-reads nothing until the project it describes actually changes,
     * and a change is picked up without anything having to invalidate this.
     *
     * Empty for a folder with no model, which is what a project made by an older build of this app is.
     */
    fun modules(root: String): List<String> {
        val model = IosFiles.join(root, MODEL_FILE)
        if (!IosFiles.exists(model)) return emptyList()
        val stamp = IosFiles.modifiedMs(model)
        cached[root]?.let { if (it.first == stamp) return it.second }
        val names = runCatching {
            ModelPersistence.load(root).projects.flatMap { p -> p.modules.map { it.name } }
        }.getOrDefault(emptyList())
        cached[root] = stamp to names
        return names
    }

    /**
     * [modules] by root: the model file's modification time, and what it said.
     *
     * Concurrent because the readers are not one thread: the picker asks from the UI, and the backend asks
     * again while opening a project. A plain map here is the kind of thing that works until it does not.
     */
    private val cached = ConcurrentMap<String, Pair<Long, List<String>>>()

    private fun toUi(t: ProjectTemplate): UiProjectTemplate = UiProjectTemplate(
        id = t.id.value,
        displayName = t.displayName,
        description = t.description,
        category = t.category.displayName,
        iconId = t.iconId,
        parameters = t.parameters().map(::toUiParam),
    )

    /**
     * The template SPI's parameters as the UI's.
     *
     * Duplicates `ProjectBackend.toUiParam` for the same reason `IosCompletionMapping` duplicates
     * `CompletionMapping`: the JVM copy lives in the JVM host, and the shared home would be a bridge module
     * between `:project-model-api` and `:ide-ui-api` that does not exist. Both are total over the sealed
     * hierarchy, so a new parameter kind breaks both rather than silently dropping in one.
     */
    private fun toUiParam(p: TemplateParameter): UiTemplateParam = when (p) {
        is TemplateParameter.Text ->
            UiTemplateParam.Text(p.key, p.label, p.default, p.placeholder, validationOf(p.validation), p.help)
        is TemplateParameter.Choice -> UiTemplateParam.Choice(
            p.key, p.label, p.options.map { UiTemplateParam.Choice.Option(it.value, it.label) }, p.defaultIndex, p.help,
        )
        is TemplateParameter.Toggle -> UiTemplateParam.Toggle(p.key, p.label, p.default, p.help)
    }

    private fun validationOf(v: TextValidation): String = when (v) {
        TextValidation.NONE -> "none"
        TextValidation.IDENTIFIER -> "identifier"
        TextValidation.PACKAGE_NAME -> "package"
        TextValidation.PROJECT_NAME -> "project"
    }

    private companion object {
        /** Who the built-ins are contributed as. The JVM hosts use a plugin id here for the same reason. */
        val BUILT_IN = PluginId("dev.ide.ios.builtin")

        /** The model file a project is recognised by, relative to its root. */
        const val MODEL_FILE = ".platform/workspace.json"
    }
}
