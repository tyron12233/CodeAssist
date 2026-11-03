package dev.ide.ui.platform

/**
 * Volume keys as cursor keys (opt-in: Editor ▸ Gestures). The phone editor installs a [handler] while the setting is
 * on and the keyboard is up; the host's key dispatch (Android's `MainActivity`) offers it the volume presses before
 * the system does. Volume up is left and down is right, matching the keys' order along a phone's edge read as
 * "earlier" and "later". Null, the keys change the volume as usual.
 */
object VolumeKeyCaret {
    /** Moves the caret one character in [direction] (-1 left, +1 right); null when volume keys are not taken. */
    @kotlin.concurrent.Volatile
    var handler: ((direction: Int) -> Unit)? = null
}
