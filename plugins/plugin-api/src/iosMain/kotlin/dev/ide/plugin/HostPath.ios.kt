package dev.ide.plugin

/** A path in the app's container, as text: what every Foundation file call takes. */
actual interface HostPath {
    val value: String
}

/** The one implementation an iOS host hands out. */
class IosHostPath(override val value: String) : HostPath {
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is HostPath && other.value == value
    override fun hashCode(): Int = value.hashCode()
}
