package dev.ide.fakecompose

/**
 * Mirrors `androidx.compose.animation`'s `enter togetherWith exit`: an INFIX extension function that a use
 * site can only reach by importing it. Staged into a jar by the infix-diagnostic test, so the check is
 * exercised over a real `@kotlin.Metadata` decode rather than the project-source model.
 */
class FakeEnter

class FakeExit

class FakeContentTransform

infix fun FakeEnter.fakeTogetherWith(exit: FakeExit): FakeContentTransform = FakeContentTransform()
