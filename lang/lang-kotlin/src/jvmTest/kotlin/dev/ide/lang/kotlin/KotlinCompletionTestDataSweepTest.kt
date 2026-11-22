package dev.ide.lang.kotlin

import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.CompletionWeigher
import dev.ide.lang.completion.STANDARD_WEIGHERS
import dev.ide.lang.completion.complete
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.index.BinarySubtypeIndex
import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.PackageTypesIndex
import dev.ide.index.PackagesIndex
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.index.nestedClassEntryToFqn
import dev.ide.index.packagePrefixes
import dev.ide.kotlin.classfile.ClassFile
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinClassNamesIndex
import dev.ide.lang.kotlin.index.KotlinMembersIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinPackageTypesIndex
import dev.ide.lang.kotlin.index.KotlinPackagesIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
import dev.ide.lang.kotlin.index.KotlinSourceSymbolsIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test

/**
 * COMPLETION PARITY with the IntelliJ Kotlin plugin, measured on the plugin's own completion test data.
 *
 * Opt-in, because it needs a checkout of the plugin's `completion/testData` directory and runs a few
 * thousand completions:
 *
 *     ./gradlew :lang-kotlin:jvmTest --tests '*KotlinCompletionTestDataSweep*' --rerun \
 *         -Dkt.completionTestData=<intellij-community>/plugins/kotlin/completion/testData
 *
 * `-Dkt.completionOnly=<substring>[,<substring>…]` narrows the run to matching cases (and prints each one's
 * verdict); `-Dkt.completionDirs=` replaces the default directory list. The per-directory pass rates and
 * the violations grouped by likely cause print to stdout; every failure, with what we offered, goes to
 * `$TMPDIR/kt-completion-sweep.txt`. The headline is the `basic` directories and `keywords`: `smart` expects SMART
 * (type-filtered) completion, which we do not have, and `weighers` the exact first-N ranking.
 *
 * THE PROJECT EACH CASE RUNS IN. A fresh source root holding the case's files, the stdlib and the JDK's
 * `java.base`, and the persistent index the IDE stands up over them (built once into a shared store), so
 * completion takes the product's path: without the index no library TYPE is found by name at all. Java
 * files of a case are compiled with the running JDK into a library jar (this backend reads Java from
 * bytecode or from the host's Java source index); a case whose Java does not compile on its own, one that
 * needs Groovy/`.properties` support, one that depends on IntelliJ's excluded-packages setting and one that
 * turns on an unreleased language feature are reported as skipped. Each case runs on its own thread with a
 * large stack and a timeout, so a hang or a crash is counted rather than fatal.
 *
 * WHAT A CASE IS. A file with a `<caret>` and the directives the plugin's own runner reads
 * (`ExpectedCompletionUtils`): `EXIST`/`EXIST_JAVA_ONLY` items that must be offered, `ABSENT` items that
 * must not, `NOTHING_ELSE` (every offered item is one of the EXIST ones), `NUMBER` (how many are offered) and
 * `WITH_ORDER` (the EXIST items appear in that order). A `Foo.fir.kt` sibling holds the K2 expectations and
 * wins over `Foo.kt`; a file carrying `// IGNORE_K2` is skipped, as the plugin's K2 runner skips it.
 * `basic/multifile` cases are a directory whose files all go in the source root, the one named after the
 * directory holding the caret; `weighers` cases bring their `Foo.DataN.kt` siblings along.
 *
 * WHAT IS COMPARED. Only the item's LOOKUP STRING (what IntelliJ matches as `lookupString`, or `itemText`
 * when a directive gives only that): our items carry a source-like label (`foo(x: Int)`), so the name is
 * taken from the item's symbol, or from the label before its parameter list. Presentation fields (tailText,
 * typeText, icon) are not compared, except that several EXIST entries for one name with different tails
 * (overloads) require that many items of that name. Keyword cases keep only keyword items, as the plugin's
 * keyword runner does. Items are ranked the way the engine ranks them (tier, the standard weighers, then
 * sort priority) and deduplicated the way it deduplicates them, so `WITH_ORDER` and the weigher cases'
 * `ORDER` judge the order a user would see.
 *
 * WHAT IS NOT MODELLED. The invocation count (`INVOCATION_COUNT`): we have one completion, which also offers
 * symbols that are not imported yet, so an ABSENT that IntelliJ only meets on a first invocation can fail
 * here and is bucketed as such. The plugin's excluded-packages setting, the mock JDK's extra classes and
 * live templates (snippet items are dropped unless the case asks for templates).
 */
class KotlinCompletionTestDataSweepTest {

    @Test
    fun completionMatchesTheIntellijPluginsTestData() {
        val root = System.getProperty("kt.completionTestData")?.let(::File) ?: return
        require(root.isDirectory) { "kt.completionTestData=$root is not a directory" }
        val only = System.getProperty("kt.completionOnly")
        val cases = discover(root).filter { only == null || only.split(",").any { o -> it.id.contains(o) } }
        println("completion sweep: ${cases.size} cases under $root")

        val results = ArrayList<Outcome>()
        for ((i, case) in cases.withIndex()) {
            val outcome = runCase(case)
            results += outcome
            if (only != null) println("  ${outcome.case.id}: ${if (outcome.passed) "PASS" else "FAIL " + outcome.problems}")
            if ((i + 1) % 250 == 0) println("  ... ${i + 1}/${cases.size}")
        }
        report(results)
    }

    // ---------------------------------------------------------------- cases

    private class Case(
        val id: String,
        /** The reporting group: the first two path segments (`basic/common`, `smart/whenEntry`). */
        val group: String,
        /** Relative path -> content (caret stripped), every file of the case. */
        val files: Map<String, String>,
        val mainPath: String,
        val caret: Int,
        val directives: Directives,
        val keywordsOnly: Boolean,
        val weigher: Boolean,
        /** Why the case cannot be judged here (an IntelliJ-only setting or file type), or null. */
        val skip: String? = null,
    )

    private class Proposal(val key: String, val tail: String?, val raw: String, val byItemText: Boolean = false)

    private class Directives(
        val exist: List<Proposal>,
        val absent: List<Proposal>,
        val nothingElse: Boolean,
        val number: Int?,
        val withOrder: Boolean,
        val order: List<String>,
        val liveTemplates: Boolean,
        val invocationCount: Int?,
    )

    private fun discover(root: File): List<Case> {
        val dirs = (System.getProperty("kt.completionDirs") ?: DEFAULT_DIRS).split(',').map { it.trim() }
        val out = ArrayList<Case>()
        for (d in dirs) {
            val dir = File(root, d)
            if (!dir.isDirectory) continue
            if (d == "basic/multifile") {
                dir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.forEach { sub ->
                    val main = File(sub, sub.name + ".kt").takeIf { it.isFile } ?: return@forEach
                    val k2 = File(sub, sub.name + ".fir.kt").takeIf { it.isFile } ?: main
                    val others = sub.walkTopDown().filter { it.isFile && it != main && it != k2 }
                        .filter { it.extension == "kt" || it.extension == "java" }
                        .filterNot { it.name.endsWith(".fir.kt") || ".after" in it.name }
                        .toList()
                    // Groovy classes, `.properties` bundles and plain files are IntelliJ language support this
                    // backend does not have; such a case is reported as skipped rather than counted.
                    val foreign = sub.walkTopDown().any { it.isFile && it.extension in setOf("groovy", "properties", "txt") }
                    makeCase(root, k2, main.relativeTo(sub).path, others.associate { it.relativeTo(sub).path to it.readText() }, d,
                        skip = if (foreign) "groovy/properties/plain-file support" else null)
                        ?.let(out::add)
                }
                continue
            }
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.forEach { f ->
                val n = f.name
                if (n.endsWith(".fir.kt") || ".after" in n || Regex("""\.Data\d*\.kt$""").containsMatchIn(n) ||
                    n.endsWith(".dependency.kt")
                ) return@forEach
                if (f.parentFile.name.endsWith("Src")) return@forEach
                val k2 = File(f.parentFile, n.removeSuffix(".kt") + ".fir.kt").takeIf { it.isFile } ?: f
                val base = n.removeSuffix(".kt")
                val siblings = f.parentFile.listFiles()!!.filter {
                    it.isFile && it.name.startsWith("$base.") && it != f && it != k2 &&
                        Regex("""^${Regex.escape(base)}\.(Data\d*|dependency)\.(kt|java)$""").matches(it.name)
                }
                makeCase(root, k2, n, siblings.associate { it.name to it.readText() }, d)?.let(out::add)
            }
        }
        return out
    }

    private fun makeCase(
        root: File, file: File, mainName: String, others: Map<String, String>, dir: String, skip: String? = null,
    ): Case? {
        val raw = file.readText().replace("\r\n", "\n")
        val caret = raw.indexOf("<caret>")
        if (caret < 0) return null
        if (raw.lineSequence().any { it.substringBefore(':').trim() == "// IGNORE_K2" }) return null
        val text = raw.replace("<caret>", "")
        val d = parseDirectives(raw)
        val weigher = dir.startsWith("weighers")
        if (!weigher && d.exist.isEmpty() && d.absent.isEmpty() && !d.nothingElse && d.number == null) return null
        if (weigher && d.order.isEmpty()) return null
        val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
        val segs = rel.split('/')
        val group = if (segs.size > 2) segs[0] + "/" + segs[1] else segs[0]
        val files = LinkedHashMap<String, String>()
        others.forEach { (p, t) -> files[p] = t.replace("\r\n", "\n").replace("<caret>", "") }
        files[mainName] = text
        // The plugin's test fixture sets EXCLUDED_PACKAGES to these two; that IntelliJ setting has no equivalent here.
        val excluded = files.values.any { "excludedPackage" in it || "ExcludedClass" in it }
        return Case(
            rel, group, files, mainName, caret, d, keywordsOnly = dir == "keywords", weigher = weigher,
            skip = skip ?: when {
                excluded -> "IntelliJ excluded-packages setting"
                // Chain completion (an unimported class's static scope behind `Bar.`) is on by default, as here; a
                // case switching that registry key off asks for a mode this backend does not have.
                "// REGISTRY: kotlin.k2.chain.completion.enabled false" in raw -> "IntelliJ registry setting"
                // Unreleased language features (`companion { }` blocks, `companion fun` extensions, rich errors),
                // switched on per test with `-XXLanguage:+`, which this backend does not model.
                UNRELEASED_FEATURES.any { "-XXLanguage:+$it" in raw } -> "unreleased language feature"
                else -> null
            },
        )
    }

    private fun parseDirectives(text: String): Directives {
        val lines = text.lineSequence().map { it.trim() }
            .filter { it.startsWith("//") }.map { it.removePrefix("//").trim() }.filter { it.isNotEmpty() }.toList()
        fun withPrefix(vararg prefixes: String): List<String> = lines.mapNotNull { line ->
            prefixes.firstNotNullOfOrNull { p ->
                if (!line.startsWith(p)) return@firstNotNullOfOrNull null
                val rest = line.substring(p.length)
                if (rest.isEmpty() || rest[0].isWhitespace() || p.last().isWhitespace()) rest.trim() else null
            }
        }
        fun proposals(vararg prefixes: String): List<Proposal> = withPrefix(*prefixes).flatMap { s ->
            when {
                s.startsWith("{") -> {
                    val fields = HashMap<String, String?>()
                    for (m in JSON_FIELD.findAll(s)) {
                        val v = m.groupValues[2]
                        fields[m.groupValues[1]] = if (v == "null") null else unescape(v.substring(1, v.length - 1))
                    }
                    val key = fields["lookupString"] ?: fields["itemText"] ?: fields["allLookupStrings"]
                    if (key == null) emptyList() else listOf(Proposal(key, fields["tailText"], s, byItemText = fields["lookupString"] == null && fields["itemText"] != null))
                }
                s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") -> listOf(Proposal(s.substring(1, s.length - 1), null, s))
                else -> s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Proposal(it, null, it) }
            }
        }
        val exist = proposals("EXIST:", "EXIST_JAVA_ONLY:")
        val javaOnly = exist.map { it.key }.toSet()
        val absent = proposals("ABSENT:", "ABSENT_JAVA:", "EXIST_JS_ONLY:", "EXIST_NATIVE_ONLY:", "EXIST_COMMON_ONLY:")
            .filter { it.key !in javaOnly }
        val number = (withPrefix("NUMBER_JAVA:").firstOrNull() ?: withPrefix("NUMBER:").firstOrNull())?.toIntOrNull()
        val order = withPrefix("ORDER:").flatMap { line ->
            val unq = if (line.length >= 2 && line.startsWith("\"") && line.endsWith("\"")) line.substring(1, line.length - 1) else null
            unq?.let { listOf(it) } ?: line.split(',').map { it.trim() }
        }
        return Directives(
            exist = exist,
            absent = absent,
            nothingElse = withPrefix("NOTHING_ELSE").isNotEmpty(),
            number = number,
            withOrder = withPrefix("WITH_ORDER").isNotEmpty(),
            order = order,
            liveTemplates = withPrefix("WITH_LIVE_TEMPLATES").isNotEmpty(),
            invocationCount = withPrefix("INVOCATION_COUNT:").firstOrNull()?.toIntOrNull(),
        )
    }

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")

    // ---------------------------------------------------------------- running

    private class Outcome(
        val case: Case,
        val passed: Boolean,
        val problems: List<String>,
        /** (cause bucket, item) for every violation, for the grouped tally. */
        val causes: List<Pair<String, String>>,
        val offered: List<String>,
        val crash: String? = null,
        val skipped: String? = null,
    )

    private fun runCase(case: Case): Outcome {
        val task = FutureTask(Callable { evaluate(case) })
        val thread = Thread(null, task, "completion-sweep", 512L * 1024 * 1024).apply { isDaemon = true }
        thread.start()
        return try {
            task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            thread.interrupt()
            Outcome(case, false, listOf("timeout"), listOf("crash: timeout" to ""), emptyList(), crash = "timeout")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            val where = cause.stackTrace.firstOrNull { it.className.startsWith("dev.ide") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            val sig = "${cause::class.simpleName} at $where"
            Outcome(case, false, listOf("crash: $sig: ${cause.message?.take(200)}"), listOf("crash: $sig" to ""), emptyList(), crash = sig)
        }
    }

    private fun evaluate(case: Case): Outcome {
        case.skip?.let { return Outcome(case, false, emptyList(), emptyList(), emptyList(), skipped = it) }
        val src = Files.createTempDirectory("kt-completion-sweep")
        try {
            // Java files are compiled into a library jar: this backend reads Java from bytecode or from the
            // host's Java source index, and the index is not stood up here.
            val javaFiles = case.files.filterKeys { it.endsWith(".java") }
            // The plugin's tests run on a JDK that has `java.sql` and the Swing/AWT packages too; a case naming
            // the desktop packages gets that (large) module, every case the small `java.sql` one.
            val jdkJars = if (case.files.values.any { "javax.swing" in it || "java.awt" in it }) jars + listOfNotNull(desktopJar) else jars
            val libs = if (javaFiles.isEmpty()) jdkJars else {
                val jar = compileJava(javaFiles, src.resolveSibling(src.fileName.toString() + "-java"))
                    ?: return Outcome(case, false, emptyList(), emptyList(), emptyList(), skipped = "java does not compile alone")
                jdkJars + listOf(jar)
            }
            for ((p, t) in case.files) {
                if (p.endsWith(".java")) continue
                val target = src.resolve(p)
                Files.createDirectories(target.parent)
                Files.writeString(target, t)
            }
            // The persistent index the IDE stands up (type names, packages, shapes, callables), so completion runs
            // the product's path rather than the index-less fallback: without it no library TYPE is found by name.
            val index = IndexServiceImpl(INDEXES, indexCache)
            runBlocking { index.ensureUpToDate(IndexScope(sourceRoots = listOf(src), libraryJars = libs)) }
            if (System.getProperty("kt.completionDebug") != null) {
                println("  libs=$libs")
                for (l in libs.drop(jars.size)) java.util.zip.ZipFile(l.toFile()).use { z -> println("  entries=" + z.entries().toList().map { it.name }) }
                println("  JavaConstructor -> " + index.exact<ClassNameValue>(ClassNameIndex.JAVA, "JavaConstructor").toList())
            }
            val analyzer = KotlinSourceAnalyzer(fakeContext(src, libs)).also { it.indexService = index }
            try {
                val text = case.files.getValue(case.mainPath)
                val doc = SnippetDoc(text, DiskFile(src.resolve(case.mainPath)))
                val all = runBlocking {
                    // Parse immediately before completing: the analyzer keeps only its most recent parses.
                    analyzer.incrementalParser.parseFull(doc)
                    analyzer.complete(CompletionRequest(doc, case.caret, CompletionTrigger.Explicit))
                }.items
                val ranked = rank(all, doc, case.caret)
                if (System.getProperty("kt.completionDebug") != null) {
                    for (it in ranked.take(12)) println("    ${it.label} kind=${it.kind} sp=${it.sortPriority} rel=${it.relevance} sym=${(it.symbol as? dev.ide.lang.kotlin.symbols.KotlinSymbol)?.let { s -> "owner=${s.owner?.name} pkg=${s.packageName} decl=${s.declaringClassFqn} src=${s.origin.fromSource} file=${s.origin.file?.path} tp=${s.typeParameters} b=${s.typeParameterBounds.map { (it as? dev.ide.lang.kotlin.symbols.KotlinType)?.let { t -> t.qualifiedName + ":" + t.isTypeParameter } }} rta=${s.receiverTypeArgs.map { (it as? dev.ide.lang.kotlin.symbols.KotlinType)?.let { t -> t.qualifiedName + ":" + t.isTypeParameter } }} rtp=${s.receiverTypeParam} rf=${s.receiverTypeFqn} dn=${s.declaration()?.let { d -> d::class.simpleName + ":" + ((d as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi?.let { p -> p::class.simpleName }) }}" }}")
                }
                return judge(case, ranked)
            } finally {
                runCatching { analyzer.dispose() }
                runCatching { index.close() }
            }
        } finally {
            src.toFile().deleteRecursively()
            src.resolveSibling(src.fileName.toString() + "-java").toFile().deleteRecursively()
        }
    }

    /** [files] compiled with the running JDK's javac into a jar under [work]; null when they do not compile. */
    private fun compileJava(files: Map<String, String>, work: Path): Path? {
        val javac = ToolProvider.getSystemJavaCompiler() ?: return null
        val srcDir = work.resolve("src")
        val classes = work.resolve("classes")
        Files.createDirectories(classes)
        val paths = files.map { (p, t) ->
            srcDir.resolve(p).also { Files.createDirectories(it.parent); Files.writeString(it, t) }.toString()
        }
        val args = listOf("-d", classes.toString(), "-cp", stdlibJarPath().toString(), "-proc:none", "-nowarn", "-parameters") + paths
        val err = java.io.ByteArrayOutputStream()
        if (javac.run(null, err, err, *args.toTypedArray()) != 0) return null
        val jar = work.resolve("java-sources.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).filter { Files.isRegularFile(it) }.forEach { f ->
                out.putNextEntry(JarEntry(classes.relativize(f).toString().replace(File.separatorChar, '/')))
                Files.copy(f, out)
                out.closeEntry()
            }
        }
        return jar
    }

    /** The engine's ranking (structural tier, the standard weighers, sort priority) and its de-duplication. */
    private fun rank(items: List<CompletionItem>, doc: SnippetDoc, offset: Int): List<CompletionItem> {
        val text = doc.text
        var i = offset
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '$')) i--
        val prefix = text.substring(i, offset)
        val params = CompletionParams(
            doc, offset, prefix, LanguageId("kotlin"), CompletionTrigger.Explicit, TextRange(i, offset), null, null,
        )
        val weighers: List<CompletionWeigher> = (listOf(TierWeigher, PriorityWeigher) + STANDARD_WEIGHERS).sortedBy { it.order }
        val weights = items.map { item -> weighers.map { it.weigh(item, params) } }
        val order = items.indices.sortedWith { a, b ->
            for (k in weighers.indices) {
                val c = weights[b][k].compareTo(weights[a][k])
                if (c != 0) return@sortedWith c
            }
            0
        }
        return order.map { items[it] }.distinctBy { listOf(it.kind, it.label, it.insertText, it.container, it.detail) }
    }

    private object TierWeigher : CompletionWeigher {
        override val id = "sweep.itemTier"
        override val order = 100
        override fun weigh(item: CompletionItem, params: CompletionParams): Double = when {
            item.kind == CompletionItemKind.WORD -> 0.0
            (item.kind == CompletionItemKind.SNIPPET || item.kind == CompletionItemKind.KEYWORD) && item.sortPriority > 0 -> 1.0
            else -> 2.0
        }
    }

    private object PriorityWeigher : CompletionWeigher {
        override val id = "sweep.sortPriority"
        override val order = 1000
        override fun weigh(item: CompletionItem, params: CompletionParams): Double = -item.sortPriority.toDouble()
    }

    /** The lookup string IntelliJ would give [item]: a keyword's or a named argument's text as is, else the name. */
    private fun primaryKey(item: CompletionItem): String = when {
        // An anonymous object of the expected type (`object : Foo{...}`) is looked up by `object`.
        item.kind == CompletionItemKind.KEYWORD && item.label.startsWith("object : ") -> "object"
        item.kind == CompletionItemKind.KEYWORD -> item.label
        item.label.endsWith(" =") -> item.label
        else -> item.symbol?.name ?: item.label.substringBefore('(').substringBefore(" {").trim()
    }

    /** Every string [item] could reasonably be looked up by, for matching an EXIST entry. */
    private fun keys(item: CompletionItem, aliases: Boolean = true): Set<String> = buildSet {
        add(primaryKey(item))
        add(item.label)
        // A use-site target is shown `get:` and looked up by `get`.
        if (item.kind == CompletionItemKind.KEYWORD && item.label.endsWith(":")) add(item.label.removeSuffix(":"))
        // An override stub is also looked up by `override` (`OverridesCompletionLookupElementDecorator`).
        if (aliases && item.detail == "override" && item.label.startsWith("override ")) add("override")
        add(item.label.substringBefore('(').substringBefore(" {").trim())
        add(item.insertText.trim())
        add(item.insertText.substringBefore('(').substringBefore('<').trim())
        item.symbol?.name?.let(::add)
        // A lambda's parameter list (`(key, value) -> `, `i, i1 -> `) is looked up by its first name, as
        // `createCompoundLookupElement` creates it.
        if (item.kind == CompletionItemKind.PARAMETER && item.insertText.endsWith(" -> ")) {
            add(item.label.removePrefix("(").substringBefore(',').substringBefore(')').trim())
        }
    }

    private fun judge(case: Case, ranked: List<CompletionItem>): Outcome {
        val d = case.directives
        var items = ranked.filter { it.kind != CompletionItemKind.WORD && (d.liveTemplates || it.kind != CompletionItemKind.SNIPPET) }
        if (case.keywordsOnly) items = items.filter { it.kind == CompletionItemKind.KEYWORD }
        val offered = items.map { primaryKey(it) }
        val problems = ArrayList<String>()
        val causes = ArrayList<Pair<String, String>>()

        if (case.weigher) {
            // assertPreferredCompletionItems: the first N lookup strings are exactly the ORDER list.
            val head = offered.take(d.order.size)
            if (head != d.order) {
                problems += "order: want ${d.order} got $head"
                val missing = d.order.filter { it !in offered }
                causes += (if (missing.isNotEmpty()) "weigher: item missing" else "weigher: wrong order") to d.order.joinToString()
            }
            return Outcome(case, problems.isEmpty(), problems, causes, offered)
        }

        val lookupKeySets = items.map { keys(it) }
        // An item named by its presentation (`itemText`) is not found through another lookup string it has.
        val textKeySets = items.map { keys(it, aliases = false) }
        fun keySetsFor(p: Proposal) = if (p.byItemText) textKeySets else lookupKeySets
        val keySets = lookupKeySets
        val matchedItems = HashSet<Int>()
        val positions = ArrayList<Int>()
        val byKey = d.exist.groupBy { it.key }
        for ((key, props) in byKey) {
            val sets = keySetsFor(props.first())
            val hits = sets.indices.filter { key in sets[it] }
            matchedItems += hits
            val need = props.map { it.tail }.distinct().size
            when {
                hits.isEmpty() -> {
                    problems += "missing: $key"
                    causes += missingCause(case, key, items) to key
                }
                hits.size < need -> {
                    problems += "overloads: $key want $need got ${hits.size}"
                    causes += "missing: fewer overloads than expected" to key
                }
            }
            if (hits.isNotEmpty()) positions += hits.first()
        }
        if (d.withOrder && byKey.keys.all { k -> keySets.any { k in it } }) {
            val firstHits = d.exist.map { p -> keySetsFor(p).indexOfFirst { p.key in it } }
            if (firstHits != firstHits.sorted()) {
                problems += "order: ${d.exist.map { it.key }} at $firstHits"
                causes += "order: WITH_ORDER violated" to d.exist.joinToString { it.key }
            }
        }
        for (p in d.absent) {
            val hit = items.indexOfFirst { primaryKey(it) == p.key && (p.tail == null || tailMatches(it, p.tail)) }
            if (hit >= 0) {
                problems += "present: ${p.key}"
                causes += presentCause(items[hit]) to p.key
            }
        }
        if (d.nothingElse) {
            val extra = items.indices.filter { it !in matchedItems }.map { items[it] }
            if (extra.isNotEmpty()) {
                problems += "nothing-else: ${extra.size} extra (${extra.take(8).joinToString { primaryKey(it) }})"
                causes += extraCause(extra) to extra.take(3).joinToString { primaryKey(it) }
            }
        }
        if (d.number != null && d.number != items.size) {
            problems += "number: want ${d.number} got ${items.size}"
            causes += (if (items.size > d.number) "number: too many" else "number: too few") to ""
        }
        return Outcome(case, problems.isEmpty(), problems, causes, offered)
    }

    private fun tailMatches(item: CompletionItem, tail: String): Boolean {
        val ours = item.label.removePrefix(primaryKey(item))
        val want = tail.substringBefore(" (").substringBefore(" for ")
        return want.isEmpty() || ours.startsWith(want) || want.startsWith("(") && ours.replace(" ", "") == want.replace(" ", "")
    }

    private fun missingCause(case: Case, key: String, items: List<CompletionItem>): String {
        val text = case.files.getValue(case.mainPath)
        var i = case.caret
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        val before = text.substring(0, i).trimEnd()
        val ctx = when {
            before.endsWith("?.") || before.endsWith(".") -> "after-dot"
            before.endsWith("::") -> "callable-ref"
            before.endsWith("@") -> "label/annotation"
            before.endsWith("import") || Regex("""import\s+[\w.]*$""").containsMatchIn(before) -> "import"
            else -> "bare"
        }
        val what = when {
            key in KEYWORDS || key.split(' ').all { it in KEYWORDS } -> "keyword"
            key.endsWith(" =") || " = " in key -> "named-arg"
            key.startsWith("override ") || key.startsWith("fun ") || key.startsWith("val ") -> "override/decl"
            ':' in key -> "param-name+type"
            else -> "symbol"
        }
        if (items.isEmpty()) return "missing: EMPTY result ($ctx)"
        return "missing $what ($ctx)"
    }

    private fun presentCause(item: CompletionItem): String = when {
        item.kind == CompletionItemKind.KEYWORD -> "present: keyword"
        item.relevance?.inScope == false -> "present: not-imported symbol"
        else -> "present: ${item.kind.name.lowercase()}"
    }

    private fun extraCause(extra: List<CompletionItem>): String {
        val kinds = extra.map {
            when {
                it.kind == CompletionItemKind.KEYWORD -> "keyword"
                it.relevance?.inScope == false -> "not-imported"
                else -> "symbol"
            }
        }.toSet()
        return "nothing-else: extra " + kinds.sorted().joinToString("+")
    }

    // ---------------------------------------------------------------- report

    private fun report(all: List<Outcome>) {
        val skipped = all.filter { it.skipped != null }
        val results = all.filter { it.skipped == null }
        val out = StringBuilder()
        fun line(s: String = "") { println(s); out.append(s).append('\n') }
        val total = results.size
        val passed = results.count { it.passed }
        line("completion sweep: $passed/$total passed (${pct(passed, total)}), crashed ${results.count { it.crash != null }}")
        line()
        line("skipped ${skipped.size}: " + skipped.groupingBy { it.skipped }.eachCount().entries.joinToString { "${it.key} ${it.value}" })
        val headline = results.filter { it.case.group.startsWith("basic") || it.case.group == "keywords" }
        line("headline (basic/* + keywords): ${headline.count { it.passed }}/${headline.size} (${pct(headline.count { it.passed }, headline.size)})")
        line(String.format("%-40s %6s %6s %7s", "group", "pass", "total", "rate"))
        for ((g, rs) in results.groupBy { it.case.group }.toSortedMap()) {
            val p = rs.count { it.passed }
            line(String.format("%-40s %6d %6d %7s", g, p, rs.size, pct(p, rs.size)))
        }
        line()
        line("violations by cause (cases affected / violations):")
        val byCause = results.flatMap { r -> r.causes.map { Triple(it.first, it.second, r.case.id) } }.groupBy { it.first }
        for ((cause, vs) in byCause.entries.sortedByDescending { e -> e.value.map { it.third }.distinct().size }) {
            val cases = vs.map { it.third }.distinct()
            line(String.format("  %-48s %5d / %5d   e.g. %s", cause, cases.size, vs.size, vs.take(3).joinToString { "${it.third.substringAfterLast('/')}:${it.second}" }))
        }
        line()
        line("violation-type counts: missing-EXIST ${results.sumOf { r -> r.problems.count { it.startsWith("missing") } }}, " +
            "present-ABSENT ${results.sumOf { r -> r.problems.count { it.startsWith("present") } }}, " +
            "NOTHING_ELSE ${results.count { r -> r.problems.any { it.startsWith("nothing-else") } }}, " +
            "NUMBER ${results.count { r -> r.problems.any { it.startsWith("number") } }}, " +
            "order ${results.count { r -> r.problems.any { it.startsWith("order") } }}, " +
            "overloads ${results.sumOf { r -> r.problems.count { it.startsWith("overloads") } }}")
        val details = StringBuilder(out)
        details.append("\n==================== FAILURES ====================\n")
        for (r in results.filterNot { it.passed }) {
            details.append("\n### ${r.case.id}\n")
            r.problems.forEach { details.append("  - ").append(it).append('\n') }
            details.append("  offered (${r.offered.size}): ").append(r.offered.take(60).joinToString(", ")).append('\n')
        }
        details.append("\n==================== PASSED ====================\n")
        results.filter { it.passed }.forEach { details.append(it.case.id).append('\n') }
        val file = System.getProperty("kt.completionOut")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "kt-completion-sweep.txt")
        file.writeText(details.toString())
        println("details: ${file.absolutePath}")
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "-" else String.format("%.1f%%", 100.0 * a / b)

    companion object {
        private const val DEFAULT_DIRS =
            "basic/common,basic/java,basic/fullJdk,basic/skipRangeTo,basic/multifile,keywords,smart,weighers/basic,weighers/smart"
        private const val TIMEOUT_SECONDS = 30L

        private val UNRELEASED_FEATURES = listOf("CompanionBlocks", "CompanionExtensions", "RichErrors", "ExplicitBackingFields", "ExplicitContextArguments")

        private val JSON_FIELD = Regex(""""?(\w+)"?\s*:\s*("(?:[^"\\]|\\.)*"|null)""")

        private val KEYWORDS = setOf(
            "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "!in",
            "interface", "is", "!is", "null", "object", "package", "return", "super", "this", "throw", "true", "try",
            "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
            "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam",
            "where", "abstract", "actual", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
            "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator",
            "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
            "value", "context", "it",
        )

        /** The library segment store, shared by every case so stdlib and the JDK are indexed once. */
        private val indexCache: Path by lazy { Files.createTempDirectory("kt-completion-sweep-idx") }

        /** The Kotlin indexes the IDE registers, plus the library halves of the `java.*` name indexes, which
         *  live in `:lang-java` (not a dependency of this module) and are mirrored below. */
        private val INDEXES: List<IndexExtension<*, *>> = listOf(
            LibraryClassNames, LibraryPackages, LibraryPackageTypes, LibraryStatics,
            KotlinClassNamesIndex, KotlinPackagesIndex, KotlinPackageTypesIndex, KotlinSourceSymbolsIndex,
            KotlinMembersIndex, KotlinTypeShapeIndex, KotlinBuiltinsIndex, KotlinCallableIndex,
            KotlinBuiltinCallableIndex, KotlinSourceCallableIndex, KotlinPackageDeclIndex,
            BinarySubtypeIndex, KotlinSourceSubtypeIndex,
        )

        private fun libraryClass(input: IndexInput): Boolean =
            (input.origin == IndexOrigin.LIBRARY || input.origin == IndexOrigin.SDK) && input.unitName?.endsWith(".class") == true

        /** The kind of a PUBLIC class file (`:lang-java`'s `publicBytecodeKind`), null for a non-public one. */
        private fun publicKind(input: IndexInput): String? {
            val cf = input.shared(IndexInput.CLASS_FILE) {
                runCatching { input.bytes() }.getOrNull()?.let { ClassFile.read(it) }
            } ?: return null
            val access = cf.accessFlags
            if (access and 0x0001 == 0) return null
            return when {
                access and 0x2000 != 0 -> "annotation"
                access and 0x0200 != 0 -> "interface"
                access and 0x4000 != 0 -> "enum"
                else -> "class"
            }
        }

        /** `java.classNames` over library class files: a type (nested ones by their own simple name). */
        private object LibraryClassNames : IndexExtension<String, ClassNameValue> {
            override val id = ClassNameIndex.JAVA
            override val version = 1
            override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
            override val valueExternalizer = ClassNameExternalizer
            override val matching = MatchingMode.PREFIX_AND_FUZZY
            override val inputFilter = InputFilter { libraryClass(it) }
            override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
                val kind = publicKind(input) ?: return emptyMap()
                val entry = input.unitName!!
                val (fqn, simple) = classEntryToFqn(entry) ?: nestedClassEntryToFqn(entry) ?: return emptyMap()
                return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, kind)))
            }
        }

        /** `java.packageTypes` over library class files: package -> its top-level public types. */
        private object LibraryPackageTypes : IndexExtension<String, ClassNameValue> {
            override val id = PackageTypesIndex.JAVA
            override val version = 1
            override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
            override val valueExternalizer = ClassNameExternalizer
            override val matching = MatchingMode.PREFIX_ONLY
            override val inputFilter = InputFilter { libraryClass(it) }
            override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
                val kind = publicKind(input) ?: return emptyMap()
                val (fqn, _) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
                val pkg = fqn.substringBeforeLast('.', "").ifEmpty { return emptyMap() }
                return mapOf(pkg to listOf(ClassNameValue(fqn, input.origin, kind)))
            }
        }

        /**
         * `java.staticMembers` over class files: member name -> the class declaring a static of that name. The
         * product indexes a library's public statics of public classes and a project `.java` file's non-private
         * ones (nested owners too); a case's Java is project source compiled into a jar here, so a class outside
         * the JDK's packages is read the project's way.
         */
        private object LibraryStatics : IndexExtension<String, String> {
            override val id = dev.ide.index.IndexId("java.staticMembers")
            override val version = 2
            override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
            override val valueExternalizer = StringExternalizer
            override val matching = MatchingMode.PREFIX_ONLY
            override val inputFilter = InputFilter { libraryClass(it) }
            override fun index(input: IndexInput): Map<String, Collection<String>> {
                val entry = input.unitName!!
                val owner = (classEntryToFqn(entry) ?: nestedClassEntryToFqn(entry))?.first ?: return emptyMap()
                val project = JDK_PACKAGES.none { owner.startsWith(it) }
                // The product's bytecode half keys top-level classes only.
                if (!project && classEntryToFqn(entry) == null) return emptyMap()
                val cf = input.shared(IndexInput.CLASS_FILE) {
                    runCatching { input.bytes() }.getOrNull()?.let { ClassFile.read(it) }
                } ?: return emptyMap()
                if (!project && cf.accessFlags and 0x0001 == 0) return emptyMap()
                val names = LinkedHashSet<String>()
                for (m in cf.methods + cf.fields) {
                    val static = m.access and 0x0008 != 0
                    val visible = if (project) m.access and 0x0002 == 0 else m.access and 0x0001 != 0
                    if (!static || !visible || m.access and 0x1000 != 0 || m.name.startsWith("<") || '$' in m.name) continue
                    names += m.name
                }
                return names.associateWith { listOf(owner) }
            }
        }

        private val JDK_PACKAGES = listOf("java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.")

        /** `java.packages` over library class files: every package prefix. */
        private object LibraryPackages : IndexExtension<String, String> {
            override val id = PackagesIndex.JAVA
            override val version = 1
            override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
            override val valueExternalizer = StringExternalizer
            override val matching = MatchingMode.PREFIX_ONLY
            override val inputFilter = InputFilter { libraryClass(it) }
            override fun index(input: IndexInput): Map<String, Collection<String>> {
                val fqn = classEntryToFqn(input.unitName!!)?.first ?: return emptyMap()
                return packagePrefixes(fqn).associateWith { listOf(it) }
            }
        }

        private val jdkJarDir: Path get() = Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar")

        private val jars: List<Path> by lazy {
            listOfNotNull(
                stdlibJarPath(),
                TestJars.jdkBaseJar(jdkJarDir),
                TestJars.jdkModuleJar(jdkJarDir, "java.sql"),
            )
        }

        private val desktopJar: Path? by lazy { TestJars.jdkModuleJar(jdkJarDir, "java.desktop") }
    }
}
