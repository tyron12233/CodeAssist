package dev.ide.build.jvm.test.fixtures

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A test superclass's hooks run first and its tests run on the subclass. */
abstract class BaseFixture {
    @BeforeEach fun baseBefore() = println("base before")
    @Test fun inherited() {}
}

class InheritedFixture : BaseFixture() {
    @BeforeEach fun ownBefore() = println("own before")
    @Test fun own() {}
}
