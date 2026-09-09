package dev.ide.awt

/**
 * `java.awt.AWTEvent`: what every event carries, the object it happened to and what kind it was.
 *
 * It lives in `dev.ide.awt`, NOT in `dev.ide.awt.event` beside the events that extend it, because the real one
 * is `java.awt.AWTEvent` and the toolkit mirrors package for package. Putting it with its subclasses would
 * remap it to `java.awt.event.AWTEvent`, which does not exist: a program importing `java.awt.AWTEvent` would
 * fail to resolve, and the generated API jar would ship three event classes with a dangling supertype.
 */
open class AWTEvent(val source: Any?, val id: Int) {
    /** AWT spells it `getID`, not `getId`, so it is declared rather than left to the [id] property. */
    fun getID(): Int = id
}
