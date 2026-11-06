package dev.ide.fakecompose

import androidx.compose.runtime.Composable

/** Like Compose's `@ExperimentalLayoutApi`: an opt-in marker whose use is an ERROR without `@OptIn`. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class FakeExperimentalLayout

/** Like `RowScope`: a member EXTENSION on the modifier, reached inside the scope's content lambda. */
interface FakeRowScope {
    fun FakeModifier.fakeAlignByBaseline(): FakeModifier
}

/** Like `ContextualFlowRowOverflowScopeImpl`: an EXPERIMENTAL class redeclaring the scope's member extension.
 *  The classpath index keys a class's member extensions by receiver, so it competed with the scope's own. */
@FakeExperimentalLayout
class FakeExperimentalRowScope : FakeRowScope {
    override fun FakeModifier.fakeAlignByBaseline(): FakeModifier = this
}

@Composable
fun FakeLayoutRow(content: @Composable FakeRowScope.() -> Unit) {
}

/** Like `spring`: a generic factory whose `T` appears only in a DEFAULTED parameter, so nothing at the call
 *  site pins it except the type the result is expected to have. */
class FakeSpec<T>

fun <T> fakeSpring(damping: Float = 1f, threshold: T? = null): FakeSpec<T> = FakeSpec()

/** Like `Transition.animateFloat(transitionSpec = { … })`: a lambda whose RESULT is a `FakeSpec<Float>`. */
fun fakeAnimateFloat(spec: () -> FakeSpec<Float>) {
}
