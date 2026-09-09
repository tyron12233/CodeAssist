package dev.ide.interp

/**
 * Resolves an Android project's resources for the interpreted Compose preview (the interpreter-mediated path).
 *
 * On device the real `android.content.res.Resources` behind `stringResource`/`painterResource`/… is the IDE
 * app's own, which does not hold the previewed project's `res/`, so those calls fail. Rather than build a real
 * project `Resources` (that is the heavier arsc-backed path), the interpreter and the Compose bridge call this
 * port instead: it is backed by the host (ide-core) with the module's merged resource repository and the same
 * aapt-shaped id assignment the synthetic `R` uses, so `R.string.foo` (via [rClassField]) and
 * `stringResource(id)` (via [string]) agree on the id.
 *
 * Null on paths with no project resources (desktop, Learn lessons); there the resource calls degrade exactly as
 * before. Every lookup returns null for an unknown resource so the caller can fall back to a visible placeholder
 * instead of throwing.
 */
interface PreviewResourceResolver {
    /**
     * The int id an `R` field denotes, given the R (sub)class fully-qualified name (`com.example.R.string`,
     * with either `.` or `$` nesting) and the field name (`app_name`); null when it is not a known project
     * resource. Lets the interpreter evaluate `R.string.app_name` without the synthetic `R` on its classpath.
     */
    fun rClassField(ownerFqn: String, fieldName: String): Int?

    /** A `@string` value for [id]. */
    fun string(id: Int): String?

    /** A `@array`/`string-array` value for [id]. */
    fun stringArray(id: Int): List<String>?

    /** A `@plurals` value for [id] selected for [quantity]. */
    fun plural(id: Int, quantity: Int): String?

    /** A `@color` [id] as a PRE-BUILT `androidx.compose.ui.graphics.Color` (returned as `Any?` so interp-core
     *  stays Compose-free — interp-compose only links `compose.runtime`, not `ui.graphics`, so the host builds
     *  it). Null if unknown. `colorResource(id)` returns this. */
    fun color(id: Int): Any?

    /** A `@dimen` [id] as a PRE-BUILT `androidx.compose.ui.unit.Dp` (density-resolved by the host), returned as
     *  `Any?`. Null if unknown. `dimensionResource(id)` returns this. */
    fun dimension(id: Int): Any?

    /** A `@drawable`/`@mipmap` [id] as a PRE-BUILT `androidx.compose.ui.graphics.painter.Painter` (the host
     *  decodes the image), returned as `Any?`. Null if unknown / undecodable. `painterResource(id)` returns
     *  this. */
    fun painter(id: Int): Any?
}
