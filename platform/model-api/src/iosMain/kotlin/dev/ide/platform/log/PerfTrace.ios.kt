package dev.ide.platform.log

/** No launcher, no command line, no seed: on iOS the persisted preference is the only way tracing turns on. */
internal actual fun perfTraceLaunchFlag(): Boolean = false
