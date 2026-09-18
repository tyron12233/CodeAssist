package dev.ide.lang.kotlin

import dev.ide.lang.LanguageId

/**
 * The language this backend answers for.
 *
 * Separate from [KotlinLanguageBackend] because the id is all most of the backend needs, while the backend
 * itself is typed by `CompilationContext` — a module of a real project — and so lives on the JVM. A postfix
 * template that says "Kotlin only" should not have to name a build to say it.
 */
object KotlinLanguage {
    val ID: LanguageId = LanguageId("kotlin")
}
