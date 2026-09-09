package dev.ide.lang.jdt

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexService
import dev.ide.testkit.defaultClassNames as kitDefaultClassNames
import dev.ide.testkit.defaultPackages as kitDefaultPackages
import dev.ide.testkit.fakeIndex as kitFakeIndex

// The deterministic fake IndexService the regression suites query for auto-import / type-position / package
// completion now lives in dev.ide.testkit (shared with index-impl and the completion tests). These thin
// re-exports keep the existing lang-jdt call sites unchanged.

fun defaultClassNames(): List<ClassNameValue> = kitDefaultClassNames()

fun defaultPackages(): List<String> = kitDefaultPackages()

fun fakeIndex(
    classNames: List<ClassNameValue> = defaultClassNames(),
    packages: List<String> = defaultPackages(),
): IndexService = kitFakeIndex(classNames, packages)
