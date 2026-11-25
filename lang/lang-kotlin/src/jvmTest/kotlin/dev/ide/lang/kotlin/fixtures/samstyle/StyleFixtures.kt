package dev.ide.lang.kotlin.fixtures.samstyle

// The shape of Compose's `androidx.compose.foundation.style` API, compiled here so a test can read it back from
// @Metadata: the single abstract method is a member EXTENSION whose receiver is the interface's own type
// parameter, and the subtype fixes that parameter. See KotlinGenericSamReceiverTest.

interface CustomStyleScope

interface StyleScope : CustomStyleScope {
    fun width(value: Int)
}

fun interface CustomStyle<in ScopeT : CustomStyleScope> {
    fun ScopeT.applyStyle()
}

fun interface Style : CustomStyle<StyleScope> {
    companion object : Style {
        override fun StyleScope.applyStyle() {}
    }
}

fun Style(vararg styles: Style): Style = styles.first()
