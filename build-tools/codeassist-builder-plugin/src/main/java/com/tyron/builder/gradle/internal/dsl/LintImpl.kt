package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.Lint
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.services.DslServices
import java.io.File
import java.util.Collections
import javax.inject.Inject

abstract class LintImpl
@Inject @WithLazyInitialization("lazyInit") constructor(private val dslServices: DslServices) :
    Lint {

    @Suppress("unused") // the call is injected by DslDecorator
    protected fun lazyInit() {
        abortOnError = true
        absolutePaths = true
        explainIssues = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
        checkDependencies = false
    }

    protected abstract val _informational: MutableSet<String>
    protected abstract val _disable: MutableSet<String>
    protected abstract val _warning: MutableSet<String>
    protected abstract val _error: MutableSet<String>
    protected abstract val _fatal: MutableSet<String>
    protected abstract val _enable: MutableSet<String>

    private val severities: Map<LintModelSeverity, MutableSet<String>>
            by lazy(LazyThreadSafetyMode.PUBLICATION) {
                mapOf(
                    LintModelSeverity.INFORMATIONAL to _informational,
                    LintModelSeverity.IGNORE to _disable,
                    LintModelSeverity.WARNING to _warning,
                    LintModelSeverity.ERROR to _error,
                    LintModelSeverity.FATAL to _fatal,
                    LintModelSeverity.DEFAULT_ENABLED to _enable,
                )
            }

    internal val severityOverridesMap: Map<String, LintModelSeverity>
        get() = Collections.unmodifiableMap(mutableMapOf<String, LintModelSeverity>().apply {
            severities.forEach { (severity, issues) ->
                issues.forEach { put(it, severity) }
            }
        })

    // A wrapper around the actual set that removes items from all other sets before setting.
    // This is to ensure that when generating the map from ID to severity, that there is only
    // one value present, and the last set in the DSL overrides any previous configuration.
    // For example, so a finalizeDsl call can force an issue to be enabled, overriding the build
    // file.
    private inner class SeverityOverrideSet(
        private val severity: LintModelSeverity
    ) : AbstractMutableSet<String>() {

        private val backing: MutableSet<String> get() = severities[severity]!!
        override fun add(element: String): Boolean {
            return backing.add(element).also {
                severities.forEach { (severity, severitySet) ->
                    if (severity != this.severity) severitySet.remove(element)
                }
            }
        }

        override fun iterator(): MutableIterator<String> = backing.iterator()
        override val size: Int get() = backing.size
    }

    override val disable: MutableSet<String> = SeverityOverrideSet(LintModelSeverity.IGNORE)

    /**
     * Method for groovy DSL to allow `disable = ...`
     *
     * The implementation first copies its `items` set argument before clearing the underlying set.
     * If it cleared the underlying set first, and the build author writes disable = disable, that
     * would have the surprising result of clearing the set. See the similar implementation in
     * [com.android.build.gradle.internal.dsl.decorator.DslDecorator.createGroovyMutatingSetter]
     */
    fun setDisable(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. disable = disable doesn't clear the set
            disable.clear()
            disable.addAll(it)
        }
    }

    fun disable(vararg items: String) {
        disable += items
    }

    override val enable: MutableSet<String> = SeverityOverrideSet(LintModelSeverity.DEFAULT_ENABLED)

    fun setEnable(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. enable = enable doesn't clear the set
            enable.clear()
            enable.addAll(it)
        }
    }

    fun enable(vararg items: String) {
        enable += items
    }

    abstract override val checkOnly: MutableSet<String>

    fun checkOnly(vararg items: String) {
        checkOnly += items
    }

    abstract override var abortOnError: Boolean
    abstract override var absolutePaths: Boolean
    abstract override var noLines: Boolean
    abstract override var quiet: Boolean
    abstract override var checkAllWarnings: Boolean
    abstract override var ignoreWarnings: Boolean
    abstract override var warningsAsErrors: Boolean
    protected abstract var _checkTestSources: Boolean
    override var checkTestSources: Boolean
        get() = _checkTestSources
        set(value) {
            _checkTestSources = value
            if (value) ignoreTestSources = false
        }
    protected abstract var _ignoreTestSources: Boolean
    override var ignoreTestSources: Boolean
        get() = _ignoreTestSources
        set(value) {
            _ignoreTestSources = value
            if (value) checkTestSources = false
        }
    abstract override var checkGeneratedSources: Boolean
    abstract override var checkDependencies: Boolean
    abstract override var explainIssues: Boolean
    abstract override var showAll: Boolean
    abstract override var checkReleaseBuilds: Boolean

    protected abstract var _lintConfigPath: String?

    final override var lintConfig: File?
        get() = _lintConfigPath?.let { File(it) }
        set(value) {
            _lintConfigPath = value?.path
        }

    abstract override var textReport: Boolean
    abstract override var htmlReport: Boolean
    abstract override var sarifReport: Boolean
    abstract override var xmlReport: Boolean

    protected abstract var _textOutputPath: String?
    final override var textOutput: File?
        get() = _textOutputPath?.let { File(it) }
        set(value) {
            _textOutputPath = value?.path
            textReport = textReport || value != null
        }

    protected abstract var _htmlOutputPath: String?
    final override var htmlOutput: File?
        get() = _htmlOutputPath?.let { File(it) }
        set(value) {
            _htmlOutputPath = value?.path
            htmlReport = htmlReport || value != null
        }

    protected abstract var _xmlOutputPath: String?
    final override var xmlOutput: File?
        get() = _xmlOutputPath?.let { File(it) }
        set(value) {
            _xmlOutputPath = value?.path
            xmlReport = xmlReport || (value != null)
        }


    protected abstract var _sarifOutputPath: String?
    final override var sarifOutput: File?
        get() = _sarifOutputPath?.let { File(it) }
        set(value) {
            _sarifOutputPath = value?.path
            sarifReport = sarifReport || value != null
        }

    protected abstract var _baselinePath: String?

    final override var baseline: File?
        get() = _baselinePath?.let { File(it) }
        set(value) { _baselinePath = value?.path }

    /**
     * Groovy affordance to allow
     * ```
     *     baseline = 'lint_baseline.xml'
     * ```
     *
     * The only permissible form in kotlin script is
     * ```
     *     baseline = file("lint_baseline.xml")
     * ```
     */
    fun setBaseline(baseline: Any) {
        this.baseline = dslServices.file(baseline)
    }

    override val informational: MutableSet<String> =
        SeverityOverrideSet(LintModelSeverity.INFORMATIONAL)

    fun informational(vararg items: String) {
        informational += items
    }

    fun setInformational(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. informational = informational doesn't clear the set
            informational.clear()
            informational.addAll(it)
        }
    }

    override val ignore: MutableSet<String>
        get() = disable

    @Deprecated("Replaced by disable")
    fun ignore(vararg items: String) {
        disable(*items)
    }

    @Deprecated("Replaced by setDisable")
    fun setIgnore(items: Collection<String>) {
        setDisable(items)
    }

    override val warning: MutableSet<String> = SeverityOverrideSet(LintModelSeverity.WARNING)

    fun warning(vararg items: String) {
        warning += items
    }

    fun setWarning(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. warning = warning doesn't clear the set
            warning.clear()
            warning.addAll(it)
        }
    }

    override val error: MutableSet<String> = SeverityOverrideSet(LintModelSeverity.ERROR)

    fun error(vararg items: String) {
        error += items
    }

    fun setError(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. error = error doesn't clear the set
            error.clear()
            error.addAll(it)
        }
    }

    override val fatal: MutableSet<String> = SeverityOverrideSet(LintModelSeverity.FATAL)

    fun fatal(vararg items: String) {
        fatal += items
    }

    fun setFatal(items: Collection<String>) {
        ArrayList(items).let { // Take a copy so e.g. fatal = fatal doesn't clear the set!
            fatal.clear()
            fatal.addAll(it)
        }
    }
}

enum class LintModelSeverity {
    FATAL,
    ERROR,
    WARNING,
    INFORMATIONAL,
    IGNORE,
    DEFAULT_ENABLED;

    companion object {
        @JvmStatic
        fun fromName(name: String): LintModelSeverity? {
            for (severity in values()) {
                if (severity.name.equals(name, ignoreCase = true)) {
                    return severity
                }
            }

            return null
        }
    }
}