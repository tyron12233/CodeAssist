package org.gradle.configurationcache

import org.gradle.internal.service.scopes.EventScope
import org.gradle.internal.service.scopes.Scopes


@EventScope(Scopes.BuildTree::class)
interface UndeclaredBuildInputListener {
    /**
     * Called when an undeclared system property read happens.
     */
    fun systemPropertyRead(key: String, value: Any?, consumer: String?)

    fun envVariableRead(key: String, value: String?, consumer: String?)
}
