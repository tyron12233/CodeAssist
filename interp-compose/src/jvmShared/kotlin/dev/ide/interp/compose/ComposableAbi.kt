package dev.ide.interp.compose

import dev.ide.interp.InterpProfile
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.OmittedArg
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflective adapter for the Compose compiler's call ABI (validated on device by
 * `dev.ide.android.spike.ComposeAbiSpikeTest`). A `@Composable fun f(a, b)` is compiled to
 * `f(a, b, Composer, int $changed [, int $default])`; this discovers that method and invokes it, supplying
 * ALL original arguments (so `$default` can be 0 — no omissions) and `0` for every trailing int
 * (`$changed = 0` conservatively forces recompute). It also drives the caller-side group the plugin would
 * emit, so a reflectively-invoked composable sits in a stable slot across recompositions.
 *
 * This is the bridge the Compose-aware interpreter dispatcher ([ComposeDispatcher]) uses to call real
 * Material3/foundation composables.
 */
object ComposableAbi {

    private const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"

    private val composableFormCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether [ownerFqn] declares a [method] in the Compose-transformed shape (it takes a `Composer`
     * parameter) — i.e. the call targets a `@Composable` function. This reflects the REAL classpath, so it is
     * a robust cross-check for the static `@Composable` flag the resolver decodes from metadata: if that flag
     * is missing (e.g. a stale symbol cache or a metadata gap) but the transformed method exists, the call is
     * still routed through the composer-threading path. Cached per owner+method.
     *
     * The `Composer` parameter is matched BY NAME (not `isAssignableFrom`), so it works even if more than one
     * `androidx.compose.runtime.Composer` class is loaded (multiple classloaders), and the class is loaded
     * WITHOUT initialization across the available loaders, so a facade's `<clinit>` can't make detection fail.
     */
    fun isComposableCall(ownerFqn: String, method: String, loader: ClassLoader? = null): Boolean {
        val key = "${System.identityHashCode(loader)}#$ownerFqn#$method"
        composableFormCache[key]?.let { return it }
        // Not loadable here yet (e.g. project deps still resolving) → don't cache; it may load later.
        val owner = loadClassNoInit(ownerFqn, loader) ?: return false
        val result = runCatching {
            owner.methods.any { m ->
                nameMatches(
                    m.name,
                    method
                ) && m.parameterTypes.any { isComposerType(it) }
            }
        }.getOrDefault(false)
        composableFormCache[key] = result // class loaded → answer is stable
        return result
    }

    /** Whether a JVM method name corresponds to the Kotlin function [kotlinName]. Kotlin MANGLES the JVM name
     *  of any function that takes/returns an inline value class (Compose's `Text` has `Color`/`TextUnit`
     *  params) to `name-<hash>` for binary-compat — so the literal name won't match; the prefix does. The hash
     *  never contains `$`, so excluding `$` skips sibling synthetics (`…$annotations`, `…$default`). */
    private fun nameMatches(jvmName: String, kotlinName: String): Boolean =
        jvmName == kotlinName || (jvmName.startsWith("$kotlinName-") && '$' !in jvmName)

    /** A `Composer` parameter — matched by simple name as a fallback too, in case a relocated/shaded build
     *  reports a package-qualified name we don't expect (we still want to detect the composer slot). */
    private fun isComposerType(c: Class<*>): Boolean =
        c.name == COMPOSER_CLASS || c.simpleName == "Composer"

    private fun loadClassNoInit(fqn: String, loader: ClassLoader? = null): Class<*>? {
        val loaders = listOfNotNull(
            loader, // the project library loader (device preview); parent = app loader, so it covers the rest too
            javaClass.classLoader,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        return loaders.firstNotNullOfOrNull { l ->
            runCatching {
                Class.forName(
                    fqn,
                    false,
                    l
                )
            }.getOrNull()
        }
    }

    /**
     * Human-readable account of what the runtime class for [ownerFqn]#[method] actually looks like — appended
     * to the error when the composer-threading path was skipped, so a failed preview reports WHY (class not
     * loadable here? no Composer-shaped overload? a relocated Composer?). Investigation aid, not control flow.
     */
    fun diagnose(ownerFqn: String, method: String, loader: ClassLoader? = null): String {
        val owner = loadClassNoInit(ownerFqn, loader)
            ?: return " [composer-path skipped: class `$ownerFqn` is not loadable from the IDE runtime here]"
        val named = owner.methods.filter { nameMatches(it.name, method) }
        val withComposer = named.count { m -> m.parameterTypes.any { isComposerType(it) } }
        val sigs = named.take(4)
            .joinToString(" ; ") { m -> m.parameterTypes.joinToString(",") { it.simpleName } }
        return " [composer-path skipped: `$ownerFqn` loaded; ${named.size} `$method` overload(s), " +
                "$withComposer with a Composer param; param-types: $sigs]"
    }

    /**
     * Invoke the transformed composable [method] on [ownerFqn], threading [composer] + trailing ints. The
     * function may declare more parameters than were supplied at the call site ([declaredParamCount], known to
     * the resolver) — the omitted ones have defaults. We bind the [originalArgs] to parameter positions (the
     * Kotlin trailing-lambda rule: a trailing lambda fills the LAST parameter), fill the rest with placeholder
     * zero values, and set the corresponding bits in the `$default` bitmask so the composable substitutes its
     * own defaults. `$changed` stays 0 (force recompute). An interpreted content lambda is converted to a
     * functional-interface proxy of its target parameter type by [lambdaProxy] (which threads the composer).
     *
     * [receiver] is the dispatch receiver for a MEMBER composable (`CardDefaults.cardColors(…)` — an instance
     * method on the object/companion); it is null for a top-level composable (a static facade method). The JVM
     * value parameters are the same in both shapes (the receiver is implicit `this`, not a parameter), so only
     * the invoke target differs.
     *
     * [receiverCount] is the number of LEADING [originalArgs] that are an extension receiver rather than a value
     * parameter — 1 for an EXTENSION composable (`RowScope.NavigationBarItem(…)`, whose transformed JVM method is
     * static with the receiver as its first param, so the caller PREPENDS the receiver to [originalArgs]), 0
     * otherwise. The Compose `$default` bitmask numbers VALUE parameters only (the receiver is excluded), so a
     * value parameter at JVM slot `i` is mask bit `i - receiverCount`.
     */
    fun call(
        ownerFqn: String,
        method: String,
        originalArgs: List<Any?>,
        composer: Any,
        declaredParamCount: Int = originalArgs.size,
        lambdaProxy: (InterpretedLambda, Class<*>) -> Any = { l, _ -> l },
        loader: ClassLoader? = null,
        receiver: Any? = null,
        receiverCount: Int = 0,
        // True when [originalArgs] are already in declaration order (named-arg reordering ran), so they bind to
        // JVM slots POSITIONALLY — never via the trailing-lambda remap. When false, [lastArgIsTrailingLambda]
        // decides whether the final lambda is a SYNTACTIC trailing lambda (binds to the last value parameter)
        // or an in-parens lambda argument (binds positionally — `Switch(checked, onCheckedChange = { … })`,
        // whose `onCheckedChange` must NOT land on the last `interactionSource` parameter).
        argsInDeclarationOrder: Boolean = false,
        lastArgIsTrailingLambda: Boolean = originalArgs.lastOrNull() is InterpretedLambda,
    ): Any? {
        InterpProfile.count("composeCall")
        val owner = loadClassNoInit(ownerFqn, loader) ?: Class.forName(ownerFqn)
        // A trailing-lambda remap (last arg → last value parameter) applies ONLY to a syntactic trailing lambda
        // on a purely positional call. Reordered (declaration-order) args bind positionally; an interior
        // OmittedArg hole likewise means the caller already placed the args. Computed HERE (before overload
        // selection) so that both the overload pick ([transformedMethod]) and the slot binding below use the
        // SAME rule — otherwise a call whose only supplied args are `value` + an in-parens `onValueChange` lambda
        // (e.g. `OutlinedTextField(value = …, onValueChange = { … })` after its `label`/`placeholder` were
        // removed, leaving no interior holes) has selection wrongly remap the lambda onto the last parameter
        // while binding does not — so selection rejects the real (`String`) overload and falls back to a
        // sibling (`TextFieldValue`) whose `value` slot the String can't fill, dropping it → `value = null` NPE.
        val trailingLambda =
            !argsInDeclarationOrder && lastArgIsTrailingLambda && originalArgs.none { it === OmittedArg }
        // Pick the transformed overload to invoke from the RUNTIME class, then derive the real value-parameter
        // count from its Composer position. We do NOT trust [declaredParamCount] as the count — the resolver
        // sees the project's classpath, which can be a different build of the library than the one loaded here
        // (e.g. project Android Compose vs. the IDE's bundled Desktop Compose); it's only a tie-break hint.
        val m = transformedMethod(owner, method, originalArgs, declaredParamCount, trailingLambda)
        val n = composerIndex(m)
        val paramTypes = m.parameterTypes
        val trailingInts = m.parameterCount - n - 1
        // Whether this composable has a `$default` mechanism: its transformed shape is `(params…, Composer,
        // int $changed…[, int $default…])`, with one `$default` int per 31 value parameters present ONLY when
        // some parameter has a default. The `$changed` ints number `ceil(n / 10)` (≥ 1) — so any trailing int
        // beyond those is a `$default` int. This gates the not-fitting-arg fallback below: a parameter can only
        // be left to "its default" when a default actually exists.
        val changedInts = maxOf(1, (n + 9) / 10)
        val hasDefaults = trailingInts > changedInts

        // Bind supplied args to parameter slots. After named-arg reordering the args are already in declaration
        // order (with `OmittedArg` holes); otherwise a trailing lambda binds to the last declared parameter.
        val k = originalArgs.size
        val slots = arrayOfNulls<Any?>(n)
        val provided = BooleanArray(n)
        for (i in 0 until k) {
            val a = originalArgs[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n) continue
            val value = if (a is InterpretedLambda) lambdaProxy(
                a,
                paramTypes[slot]
            ) else boxValueClassIfNeeded(a, paramTypes[slot])
            // A supplied value that can't fit its parameter would make the reflective invoke throw an
            // argument-type mismatch that unwinds the WHOLE composition (a hard "preview failed" instead of a
            // partial render) — e.g. a value-class `long` landing on the typed `Modifier` slot (a placeholder/
            // mis-evaluated arg, or a named arg bound to a slot whose runtime overload differs from the
            // resolver's decoded signature). When the composable HAS defaults, drop the non-fitting arg: the
            // slot stays unprovided, so the composable substitutes its own default (via the `$default` bit set
            // below) and the preview renders. When it has NO defaults there's nothing to fall back to, so bind
            // it anyway and let the honest mismatch surface (a genuinely malformed call should still report).
            if (a is InterpretedLambda || !hasDefaults || fitsParam(value, paramTypes[slot])) {
                slots[slot] = value
                provided[slot] = true
            }
        }
        for (i in 0 until n) if (!provided[i]) slots[i] = zeroValue(paramTypes[i])

        val args = ArrayList<Any?>(m.parameterCount)
        args.addAll(slots)
        args.add(composer)
        // The `$default` mask numbers VALUE parameters only — an extension/dispatch receiver prepended into the
        // slots (receiverCount) is excluded, so a value param at slot `i` is bit `i - receiverCount`.
        val valueParamCount = n - receiverCount
        if (provided.all { it }) {
            // Every parameter supplied: $changed = 0 (force recompute), $default = 0 (nothing omitted).
            repeat(trailingInts) { args.add(0) }
        } else {
            // Omitted parameters → set their bits in the trailing `$default` ints (the LAST ints; one bit per
            // value parameter, 31 per int). The `$changed` ints before them stay 0.
            val defaultIntCount =
                (valueParamCount + BITS_PER_DEFAULT_INT - 1) / BITS_PER_DEFAULT_INT
            val changedIntCount = trailingInts - defaultIntCount
            require(changedIntCount >= 0) { "unexpected ABI for `$method`: $trailingInts trailing ints, n=$n" }
            repeat(changedIntCount) { args.add(0) }
            for (intIdx in 0 until defaultIntCount) {
                var mask = 0
                for (i in receiverCount until n) {
                    val vp = i - receiverCount
                    if (!provided[i] && vp / BITS_PER_DEFAULT_INT == intIdx) mask =
                        mask or (1 shl (vp % BITS_PER_DEFAULT_INT))
                }
                args.add(mask)
            }
        }
        m.isAccessible = true
        // A static facade method ignores the receiver (null); a member composable is invoked on its instance.
        return try {
            InterpProfile.span("composeABI") { m.invoke(receiver, *args.toTypedArray()) }
        } catch (e: IllegalArgumentException) {
            // Surface WHICH argument's runtime type didn't fit its parameter, Java's bare "argument type
            // mismatch" gives no slot, masking a wrong-typed evaluated value (e.g. an enum/object read that
            // produced the wrong type, or a value class left unboxed) deep in a composition.
            throw IllegalArgumentException(
                "ABI invoke mismatch for ${m.declaringClass.name}.${m.name}: " +
                        "params=${m.parameterTypes.map { it.simpleName }} args=${args.map { it?.javaClass?.name ?: "null" }}",
                e,
            )
        }
    }

    /**
     * Read a `@Composable` property getter on [receiver], threading [composer]. A composable property compiles
     * its getter to an instance method `get<Name>(Composer, int $changed)` (zero value parameters before the
     * Composer; commonly `@ReadOnlyComposable`, so no caller-side group is needed) — e.g.
     * `MaterialTheme.colorScheme` → `getColorScheme(Composer, int)`. The name is matched mangling-aware (a
     * value-class-typed property mangles the getter, like a value-class function). `$changed` is passed `0`
     * (force recompute). Returns the value, or null if [receiver] has no composer-taking getter for
     * [propertyName] (i.e. it's a plain property the interpreter should read directly). The name is mangled-
     * aware and the Composer matched by name, mirroring [isComposableCall].
     */
    fun readComposableProperty(receiver: Any, propertyName: String, composer: Any): Any? {
        val getter =
            "get" + propertyName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = receiver.javaClass.methods.firstOrNull { method ->
            nameMatches(method.name, getter) && composerIndex(method) == 0 &&
                    (1 until method.parameterCount).all { method.parameterTypes[it] == Int::class.javaPrimitiveType }
        } ?: return NotComposableProperty
        // Shape: (Composer, $changed…) — no value params. Thread the composer, zero the trailing $changed ints.
        val args = ArrayList<Any?>(m.parameterCount)
        args.add(composer)
        repeat(m.parameterCount - 1) { args.add(0) }
        m.isAccessible = true
        return m.invoke(receiver, *args.toTypedArray())
    }

    /** Sentinel returned by [readComposableProperty] when the property isn't a composable getter (so the value
     *  itself — possibly `null` — stays distinguishable from "not handled"). */
    val NotComposableProperty = Any()

    /**
     * Box an unboxed inline-value-class value for a NULLABLE/boxed value-class parameter. An inline value class
     * (`Color`, `Dp`, `TextUnit`, `TextAlign`) is represented UNBOXED (its underlying primitive) for a non-null
     * parameter, but BOXED for a nullable one (`textAlign: TextAlign?` → JVM type `TextAlign`). A value-class
     * expression like `TextAlign.Center` evaluates to the unboxed underlying value (the mangled `getCenter-…()`
     * getter returns the `int`), so passing it to the boxed parameter reflectively would throw an argument-type
     * mismatch — which, unwinding through a native composable, corrupts the slot table. When [paramType] is a
     * value class (has the synthetic static `box-impl`) and [value] is its underlying primitive, box it. Values
     * already of the right type, nulls, and unboxed-primitive parameters pass through unchanged.
     */
    private fun boxValueClassIfNeeded(value: Any?, paramType: Class<*>): Any? {
        if (value == null || paramType.isInstance(value)) return value
        // Inverse of boxing: a BOXED value-class instance reaching a parameter typed as its UNBOXED underlying —
        // a mangled `RecordColor-…(long, …)` / `padding-…(…, float)` wants the primitive, not the boxed value
        // class. `colorResource`/`dimensionResource` hand back pre-built boxed `Color`/`Dp`, so unbox to fit the
        // param; RECURSIVE because `Color` wraps `ULong` wraps `long`. Found on ART (this is a SEPARATE copy from
        // the reflective dispatcher's [dev.ide.interp] boxValueClassIfNeeded — the composable path is bound here).
        unboxToUnderlying(value, paramType)?.let { return it }
        if (paramType.isPrimitive) return value
        val box = paramType.methods.firstOrNull {
            it.name == "box-impl" && Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 && boxed(it.parameterTypes[0]).isInstance(value)
        } ?: return value
        box.isAccessible = true
        return box.invoke(null, value)
    }

    /** If [value] is a BOXED inline value class and [paramType] wants the unboxed underlying (a primitive), unbox
     *  via `unbox-impl` until it fits — recursively, since a value class can wrap another (`Color`→`ULong`→`long`).
     *  Null when [value] isn't a value-class box or can't be unboxed to [paramType]. */
    private fun unboxToUnderlying(value: Any, paramType: Class<*>): Any? {
        var current: Any = value
        repeat(8) {
            val cls = current.javaClass
            if (cls.declaredMethods.none { it.name == "box-impl" && Modifier.isStatic(it.modifiers) }) return null
            val unbox = cls.methods.firstOrNull {
                it.name == "unbox-impl" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers)
            } ?: return null
            runCatching { unbox.isAccessible = true }
            current = runCatching { unbox.invoke(current) }.getOrNull() ?: return null
            if (boxed(paramType).isInstance(current)) return current
        }
        return null
    }

    /** A JVM-valid placeholder for an omitted (defaulted) parameter — the composable ignores it because its
     *  `$default` bit is set, but the reflective invoke still needs a value of the right primitive kind. */
    private fun zeroValue(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> ' '
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        Boolean::class.javaPrimitiveType -> false
        else -> null
    }

    /** Index of the (first) `Composer` parameter of a transformed composable — i.e. the real value-parameter
     *  count. Matched by name so it's classloader-independent. -1 if there is none. */
    private fun composerIndex(m: Method): Int = m.parameterTypes.indexOfFirst { isComposerType(it) }

    /**
     * The transformed composable to invoke: a `[name]` method whose `Composer` sits AFTER all the supplied
     * args (so they bind to real value params) with only trailing `int`s after it, and whose leading params
     * accept the supplied args (disambiguating `Text(String,…)` from `Text(AnnotatedString,…)`). The
     * value-param count is the Composer's index — read off the runtime method, not assumed. Among matches,
     * prefer the one whose count equals [preferredParamCount] (the resolver's pick), else the fewest params.
     */
    private fun transformedMethod(
        owner: Class<*>,
        name: String,
        suppliedArgs: List<Any?>,
        preferredParamCount: Int,
        trailingLambda: Boolean
    ): Method {
        // This `owner.methods` scan + overload pick runs for every composable call on every recomposition, yet
        // it's deterministic given the owner, name, the args' runtime-TYPE shape, the preferred count, and
        // whether the last arg is a trailing lambda (which changes the slot each arg is checked against) — so
        // cache it. Keyed on the Class IDENTITY (a relocated/project-loader Composer build must not alias the
        // bundled one). A successful pick is stable; a miss `error`s and isn't cached.
        val cache = transformedCache.getOrPut(owner) { ConcurrentHashMap() }
        val key = "$name|$preferredParamCount|$trailingLambda|${argShape(suppliedArgs)}"
        cache[key]?.let { InterpProfile.count("composeCacheHit"); return it }
        InterpProfile.count("composeCacheMiss")
        val k = suppliedArgs.size
        val shaped = owner.methods.filter { m ->
            nameMatches(m.name, name) && composerIndex(m).let { ci ->
                ci >= k && (ci + 1 until m.parameterCount).all { m.parameterTypes[it] == Int::class.javaPrimitiveType }
            }
        }
        val accepting =
            shaped.filter { firstParamsAccept(it, suppliedArgs, composerIndex(it), trailingLambda) }
                .ifEmpty { shaped }
        val chosen = accepting
            .sortedWith(
                compareBy(
                    { if (composerIndex(it) == preferredParamCount) 0 else 1 },
                    { composerIndex(it) })
            )
            .firstOrNull()
            ?: error("no transformed `$name` (Composer-form) accepting $k arg(s) on ${owner.name}")
        return chosen.also { cache[key] = it }
    }

    private val transformedCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Method>>()

    /** A stable key for [args] by runtime type — enough to reselect the same overload (distinguishing null, an
     *  omitted/default slot, an interpreted lambda, and each concrete class). */
    private fun argShape(args: List<Any?>): String = buildString {
        for (a in args) {
            when {
                a == null -> append('∅')
                a === OmittedArg -> append('_')
                a is InterpretedLambda -> append('λ')
                else -> append(a.javaClass.name)
            }
            append(';')
        }
    }

    /** Whether the supplied args (bound by position, trailing lambda → last slot) fit a candidate's first [n]
     *  parameter types — a lambda fits any interface; a null fits any reference type. [trailingLambda] MUST be
     *  the same value [call] uses to bind, so overload selection and slot binding agree on where the final
     *  lambda lands (a mismatch picks an overload the binding then can't fill). */
    private fun firstParamsAccept(
        m: Method,
        suppliedArgs: List<Any?>,
        n: Int,
        trailingLambda: Boolean
    ): Boolean {
        val k = suppliedArgs.size
        for (i in 0 until k) {
            val a = suppliedArgs[i]
            if (a === OmittedArg) continue // an omitted slot fits any param (filled from $default)
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n) return false
            val p = m.parameterTypes[slot]
            when (a) {
                // A lambda fits only a FUNCTIONAL interface (a `kotlin.Function*` / SAM) — NOT any interface. A
                // trailing content lambda (`Box { … }`) must not be considered a fit for a non-functional interface
                // parameter like `Modifier` (an interface with several abstract methods): that would let the
                // content-less `Box(modifier: Modifier)` overload "accept" the lambda and win the fewest-params
                // tiebreak over the real content-taking `Box(…, content)`, binding the lambda onto the `modifier`
                // slot → `materializeModifier` calls `.all(…)` on the proxy, which returns null → NPE (`Box.kt`).
                is InterpretedLambda -> if (!isFunctionalInterface(p)) return false
                null -> if (p.isPrimitive) return false
                // A boxed value-class parameter (`TextAlign?`) accepts the unboxed underlying value too.
                else -> if (!boxed(p).isInstance(a) && !acceptsValueClassUnderlying(
                        p,
                        a
                    )
                ) return false
            }
        }
        return true
    }

    /** Whether [value] (already run through [boxValueClassIfNeeded]) can be passed to a parameter of [paramType]
     *  by reflection: null fits any reference; a boxed value fits a primitive of the same kind (the invoke
     *  unboxes); otherwise the value must be an instance of the (boxed) parameter type, or an unboxed value-class
     *  underlying that still needs boxing. Used to skip a non-fitting supplied arg (falling back to the default)
     *  rather than letting the reflective invoke throw and unwind the composition. */
    private fun fitsParam(value: Any?, paramType: Class<*>): Boolean = when {
        value == null -> !paramType.isPrimitive
        paramType.isPrimitive -> boxed(paramType).isInstance(value)
        paramType.isInstance(value) -> true
        else -> acceptsValueClassUnderlying(paramType, value)
    }

    /** Whether [c] is a FUNCTIONAL interface an interpreted lambda can be proxied into — a Kotlin function type
     *  (`kotlin.Function*` / `kotlin.jvm.functions.Function*`, the shape a transformed `@Composable` content
     *  lambda takes) or any single-abstract-method (SAM) interface. A non-functional interface with several
     *  abstract methods (e.g. `androidx.compose.ui.Modifier`) is NOT one: a lambda proxy of it returns null for
     *  every method the caller invokes, which crashes deep in the library (see [firstParamsAccept]). Cached. */
    private fun isFunctionalInterface(c: Class<*>): Boolean {
        if (!c.isInterface) return false
        return functionalInterfaceCache.getOrPut(c) {
            val n = c.name
            if (n.startsWith("kotlin.Function") || n.startsWith("kotlin.jvm.functions.Function")) true
            else c.methods.count { Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) } == 1
        }
    }

    private val functionalInterfaceCache = ConcurrentHashMap<Class<*>, Boolean>()

    /** Whether [paramType] is an inline value class whose underlying type accepts [value] — so an unboxed
     *  value-class value fits a boxed value-class parameter (it'll be boxed by [boxValueClassIfNeeded]). */
    private fun acceptsValueClassUnderlying(paramType: Class<*>, value: Any?): Boolean {
        val box = paramType.methods.firstOrNull {
            it.name == "box-impl" && Modifier.isStatic(it.modifiers) && it.parameterCount == 1
        } ?: return false
        return boxed(box.parameterTypes[0]).isInstance(value)
    }

    private fun boxed(c: Class<*>): Class<*> = when (c) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Char::class.javaPrimitiveType -> Character::class.java
        Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        Short::class.javaPrimitiveType -> java.lang.Short::class.java
        else -> c
    }

    private const val BITS_PER_DEFAULT_INT = 31

    fun startGroup(composer: Any, key: Int): Unit = InterpProfile.span("composeGroup") {
        startGroupCache.getOrPut(composer.javaClass) {
            groupMethod(
                composer,
                setOf("startReplaceableGroup", "startReplaceGroup"),
                paramCount = 1
            )
        }.invoke(composer, key)
    }

    fun endGroup(composer: Any): Unit = InterpProfile.span("composeGroup") {
        endGroupCache.getOrPut(composer.javaClass) {
            groupMethod(composer, setOf("endReplaceableGroup", "endReplaceGroup"), paramCount = 0)
        }.invoke(composer)
    }

    // --- composition recovery (unwind a composable that threw mid-composition) ---

    /** `composer.getCurrentMarker(): Int` — an opaque token for the composer's current group/node position.
     *  Captured before invoking a composable so [endToMarker] can restore this exact position if the
     *  composable throws while it has groups/nodes open. */
    fun currentMarker(composer: Any): Int =
        currentMarkerCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "getCurrentMarker" && it.parameterCount == 0 }
        }.invoke(composer) as Int

    /** `composer.endToMarker(marker)` — end every group/node opened since [marker] was captured, restoring the
     *  composer to that position. The interpreter calls this when a composable throws mid-composition (leaving a
     *  node/group dangling), so the ENCLOSING composition — the IDE's own UI around the preview — is not left
     *  corrupted (which surfaces as "Cannot end node insertion" when the host closes its own node). Best-effort:
     *  a throw between `startReusableNode` and `createNode` can't be fully unwound, so callers guard it. */
    fun endToMarker(composer: Any, marker: Int) {
        endToMarkerCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "endToMarker" && it.parameterCount == 1 }
        }.invoke(composer, marker)
    }

    private val currentMarkerCache = ConcurrentHashMap<Class<*>, Method>()
    private val endToMarkerCache = ConcurrentHashMap<Class<*>, Method>()

    // --- restart groups (granular recomposition) ---

    /** `composer.startRestartGroup(key): Composer` — opens a restartable group and returns the composer to
     *  use inside it. */
    fun startRestartGroup(composer: Any, key: Int): Any = InterpProfile.span("composeGroup") {
        startRestartGroupCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "startRestartGroup" && it.parameterCount == 1 }
        }.invoke(composer, key) ?: error("startRestartGroup returned null")
    }

    /** `composer.endRestartGroup(): ScopeUpdateScope?` — the scope whose [updateScope] re-runs this group on
     *  recomposition, or null if nothing observable was read (then it can't recompose). */
    fun endRestartGroup(composer: Any): Any? = InterpProfile.span("composeGroup") {
        endRestartGroupCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "endRestartGroup" && it.parameterCount == 0 }
        }.invoke(composer)
    }

    // --- $changed skipping (the recomposition fast path) ---

    /**
     * Record each of [args] into the restart group's slot table via `composer.changed(Any?)` and report
     * whether ANY of them differs from the previous composition — the interpreter's stand-in for the
     * compiler-computed `$dirty` bitmask. Every arg is offered to `changed` unconditionally (no short-circuit):
     * the calls advance fixed slot positions, so skipping one would desync the slot table on the next pass.
     * `changed` compares with `equals`, so a remembered/identical instance reports unchanged. Empty args → not
     * changed (a no-arg composable's recompositions are entirely state-driven).
     */
    fun argsChanged(composer: Any, args: List<Any?>): Boolean {
        if (args.isEmpty()) return false
        val changed = changedMethodCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first {
                it.name == "changed" && it.parameterCount == 1 && it.parameterTypes[0] == Any::class.java
            }
        }
        var dirty = false
        for (a in args) dirty = (changed.invoke(composer, a) as Boolean) || dirty
        return dirty
    }

    /** `composer.getSkipping(): Boolean` — true when the runtime invited this group to skip (it was reached by
     *  a parent recomposition, NOT invalidated by a state read of its own). A state-driven recompose reports
     *  false here, so the body always re-runs then. */
    fun isSkipping(composer: Any): Boolean =
        skippingGetterCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "getSkipping" && it.parameterCount == 0 }
        }.invoke(composer) as Boolean

    /** `composer.skipToGroupEnd()` — abandon re-execution of this group's body, reusing last composition's
     *  nodes. Called in place of the interpreted body when the group is skippable and unchanged. */
    fun skipToGroupEnd(composer: Any) {
        skipToGroupEndCache.getOrPut(composer.javaClass) {
            composer.javaClass.methods.first { it.name == "skipToGroupEnd" && it.parameterCount == 0 }
        }.invoke(composer)
    }

    private val changedMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val skippingGetterCache = ConcurrentHashMap<Class<*>, Method>()
    private val skipToGroupEndCache = ConcurrentHashMap<Class<*>, Method>()

    // The group open/close/restart reflection runs on EVERY composable call, every pass — a fresh
    // `javaClass.methods` scan each time was the dominant preview cost on ART (profiled: ~86% of first render,
    // ~92% of recompose). Cache the resolved method per composer/scope class (identity-keyed, multi-loader-safe),
    // exactly like `changedMethodCache`/`skippingGetterCache` above.
    private val startGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val endGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val startRestartGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val endRestartGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val updateScopeCache = ConcurrentHashMap<Class<*>, Method>()

    /** Register the recomposition block: when the scope is invalidated (a state it read changed), the real
     *  Recomposer calls [recompose] with a fresh composer. No-op if [scope] is null. */
    fun updateScope(scope: Any?, recompose: (Any) -> Unit) {
        if (scope == null) return
        // The runtime expects a `(Composer, Int) -> Unit`; a Kotlin lambda is a Function2 (generics erase).
        val block: Function2<Any?, Any?, Unit> = { composer, _ -> composer?.let(recompose) }
        InterpProfile.span("composeGroup") {
            updateScopeCache.getOrPut(scope.javaClass) {
                scope.javaClass.methods.first { it.name == "updateScope" && it.parameterCount == 1 }
            }.invoke(scope, block)
        }
    }

    private fun groupMethod(composer: Any, names: Set<String>, paramCount: Int): Method =
        composer.javaClass.methods.firstOrNull { it.name in names && it.parameterCount == paramCount }
            ?: error("no ${names.joinToString("/")}($paramCount-arg) on ${composer.javaClass.name}")
}
