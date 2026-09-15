package dev.ide.ios.store

import platform.Foundation.NSUserDefaults

/**
 * Small durable values: the anonymous install id, the saved-projects list.
 *
 * `NSUserDefaults` is the right home for exactly this and the wrong one for a credential, which is why
 * the refresh token goes to the keychain instead ([IosTokenStore]).
 */
class IosPreferences(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) {

    fun get(key: String): String? = defaults.stringForKey(key)

    fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}
