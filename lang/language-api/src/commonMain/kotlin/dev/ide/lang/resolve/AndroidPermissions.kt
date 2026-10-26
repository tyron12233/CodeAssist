package dev.ide.lang.resolve

/**
 * Which Android platform APIs need a permission: the `@RequiresPermission` facts in a platform's
 * `data/annotations.zip`, which `android.jar` itself does not carry. The editor reads them for the
 * missing-permission check (Android lint's `MissingPermission`).
 *
 * Owner classes are dotted (`android.location.LocationManager`, a nested class as `Outer.Inner`). A null answer
 * means "no requirement known", never "none needed". The host injects an implementation (the table ships with
 * the Android support); [NONE] is the default.
 */
interface PlatformPermissions {
    /** What calling [name] with [arity] parameters on [ownerClass] requires, or null; `<init>` names a constructor. */
    fun requirement(ownerClass: String, name: String, arity: Int): PermissionRequirement?

    /**
     * Whether any requirement is on a method called [name], or a constructor of a class whose simple name it is:
     * a cheap test a check makes before resolving a call, since almost none of them can match.
     */
    fun mayRequire(name: String): Boolean = true

    companion object {
        val NONE: PlatformPermissions = object : PlatformPermissions {
            override fun requirement(ownerClass: String, name: String, arity: Int): PermissionRequirement? = null
            override fun mayRequire(name: String): Boolean = false
        }
    }
}

/**
 * One API's permission requirement: every one of [permissions] when [allRequired] (`value` / `allOf`), else any
 * one of them (`anyOf`). [maxApi] and [minApi] bound the platform versions it applies on (`apis = "..22"`), 0
 * when unbounded.
 */
data class PermissionRequirement(
    val permissions: List<String>,
    val allRequired: Boolean,
    val minApi: Int = 0,
    val maxApi: Int = 0,
) {
    /** Whether an app that runs on [minSdk] and up meets a device where this applies. */
    fun appliesFrom(minSdk: Int): Boolean = maxApi == 0 || maxApi >= minSdk

    /** Whether [declared] holds enough of [permissions]. */
    fun isSatisfiedBy(declared: Set<String>): Boolean =
        if (allRequired) permissions.all { it in declared } else permissions.any { it in declared }

    /**
     * Whether the user can turn this requirement down at run time: one of the permissions that grant it is a
     * dangerous one. With [declared] the app's own, only those count; null (a library) counts them all.
     */
    fun isRevocable(declared: Set<String>?): Boolean {
        val granting = if (declared == null || allRequired) permissions else permissions.filter { it in declared }
        return granting.any(PermissionGuards::isDangerous)
    }
}

/**
 * What the missing-permission check needs about the module being analyzed: the platform's [table], the
 * permissions the app declares (its manifest merged with its libraries'), and its SDK levels. [checkManifest]
 * is false for a library module, whose app declares the permissions. [permissionLevel] is the API level that
 * introduced a permission (`BLUETOOTH_SCAN` → 31), null when unknown: a permission newer than [targetSdk] is not
 * enforced for the app, which keeps the old behaviour until it targets that level.
 */
data class PermissionContext(
    val table: PlatformPermissions,
    val declared: Set<String>,
    val minSdk: Int,
    val targetSdk: Int,
    val checkManifest: Boolean = true,
    val permissionLevel: (String) -> Int? = { null },
) {
    /** Changes whenever a fact the check reads does, so a cached result can tell it is stale. */
    val stamp: Long
        get() = declared.sorted().hashCode().toLong() * 31 * 31 * 31 +
            minSdk * 31 * 31 + targetSdk * 31 + (if (checkManifest) 1 else 0)
}

/**
 * The missing-permission rules Java and Kotlin share. A language backend finds the call and what surrounds it
 * in its own tree and hands the facts here, so both editors agree on what is reported and what counts as
 * handling it:
 *  - a requirement the manifest does not meet is reported as missing;
 *  - a met requirement on a dangerous permission (targetSdk 23 and up) is reported unless the enclosing function
 *    checks a permission ([checksPermission]), the call sits in a `try` that catches `SecurityException`, or an
 *    enclosing declaration carries `@RequiresPermission` (its callers are checked instead).
 * `@SuppressLint("MissingPermission")` and `@Suppress`/`@SuppressWarnings` with the same id silence both.
 */
object PermissionGuards {

    /** The diagnostic code both editors report the check under, so quick fixes can key off it. */
    const val MISSING_PERMISSION = "android.missingPermission"

    /** Android lint's id, which `@SuppressLint` names. */
    const val LINT_ID = "MissingPermission"

    /** The first platform level with run-time permissions. */
    const val RUNTIME_PERMISSIONS_API = 23

    /** The message of a call whose permission the user may deny and nothing checks, before the permissions. */
    const val UNCHECKED_MESSAGE = "Call requires permission which may be rejected by user: code should explicitly " +
        "check to see if permission is available (with `checkPermission`) or explicitly handle a potential " +
        "`SecurityException`"

    private const val MISSING_PREFIX = "Missing permissions required by "
    private const val NEEDS = " (needs "

    /**
     * The diagnostic message for calling [label] (`LocationManager.getLastKnownLocation`), which needs [req], or
     * null when nothing is wrong. [handled] is asked only when the answer depends on it.
     */
    fun check(requirement: PermissionRequirement, ctx: PermissionContext, label: String, handled: () -> Boolean): String? {
        if (!requirement.appliesFrom(ctx.minSdk)) return null
        // A permission introduced after the targetSdk is not enforced on the app: an all-of list needs only the
        // rest, and an any-of list is met by the one the app is spared.
        val enforced = requirement.permissions.filter { (ctx.permissionLevel(it) ?: 0) <= ctx.targetSdk }
        if (enforced.isEmpty() || (!requirement.allRequired && enforced.size < requirement.permissions.size)) return null
        val req = if (enforced.size == requirement.permissions.size) requirement else requirement.copy(permissions = enforced)
        if (ctx.checkManifest && !req.isSatisfiedBy(ctx.declared)) {
            val missing = if (req.allRequired) req.permissions.filter { it !in ctx.declared } else req.permissions
            return MISSING_PREFIX + label + ": " + missing.joinToString(if (req.allRequired) " and " else " or ")
        }
        if (ctx.targetSdk < RUNTIME_PERMISSIONS_API) return null
        if (!req.isRevocable(if (ctx.checkManifest) ctx.declared else null)) return null
        if (handled()) return null
        // The ones to check: an any-of list is granted through the permissions the app declares.
        val granting = if (req.allRequired || !ctx.checkManifest) req.permissions else req.permissions.filter { it in ctx.declared }
        return UNCHECKED_MESSAGE + NEEDS + granting.joinToString(if (req.allRequired) " and " else " or ") + ")"
    }

    /** Whether [message] reports a permission the manifest lacks (as opposed to one nothing checks). */
    fun isMissingFromManifest(message: String): Boolean = message.startsWith(MISSING_PREFIX)

    /**
     * The permissions a check [message] names, and whether any one of them would do (`a or b`) rather than all
     * of them: the ones the manifest lacks, or the ones a run-time check has to ask about.
     */
    fun permissionsNamedIn(message: String): Pair<List<String>, Boolean> {
        val list = when {
            message.startsWith(MISSING_PREFIX) -> message.substringAfter(": ", "")
            message.startsWith(UNCHECKED_MESSAGE) -> message.substringAfter(NEEDS, "").removeSuffix(")")
            else -> ""
        }
        val anyOf = " or " in list
        return list.split(" or ", " and ").map { it.trim() }.filter { it.isNotEmpty() } to anyOf
    }

    /**
     * Whether a function's text checks a permission: a call like `checkSelfPermission`, `checkPermission`,
     * `PermissionChecker.checkCallingOrSelfPermission`, `enforceCallingPermission`, an app's own
     * `hasLocationPermission()` / `isPermissionGranted()`, a comparison with `PERMISSION_GRANTED`, or a Compose
     * permission state's `isGranted`. Generous on purpose, as lint is: a check that happens to look different is
     * not worth a false warning on correct code.
     */
    fun checksPermission(functionText: String): Boolean {
        if ("PERMISSION_GRANTED" in functionText || "PERMISSION_DENIED" in functionText) return true
        for (m in IDENTIFIER.findAll(functionText)) {
            val id = m.value
            if (id == "isGranted" || id == "allPermissionsGranted") return true
            val lower = id.lowercase()
            if ("permission" !in lower) continue
            if ("check" in lower || "enforce" in lower || "granted" in lower || "denied" in lower) return true
            if (lower.startsWith("has") || lower.startsWith("is") || lower.startsWith("can")) return true
        }
        return false
    }

    /** Whether a `catch` of the type written [typeText] (a Java multi-catch too) catches a `SecurityException`. */
    fun catchesSecurityException(typeText: String): Boolean =
        typeText.split('|').any { it.trim().substringAfterLast('.') in SECURITY_CATCHES }

    /** Whether the annotation [name], with arguments written [argumentsText], silences this check. */
    fun suppresses(name: String, argumentsText: String?): Boolean {
        val simple = name.substringAfterLast('.')
        if (simple != "SuppressLint" && simple != "SuppressWarnings" && simple != "Suppress") return false
        val args = argumentsText ?: return false
        return "\"$LINT_ID\"" in args || "\"$MISSING_PERMISSION\"" in args || "\"all\"" in args
    }

    /** Whether the annotation [name] is a `@RequiresPermission`, which hands the requirement to the callers. */
    fun isRequiresPermission(name: String): Boolean = name.substringAfterLast('.') == "RequiresPermission"

    /** Whether [permission] is one the user grants at run time (protection level `dangerous`). */
    fun isDangerous(permission: String): Boolean =
        permission in DANGEROUS || permission.startsWith("android.permission.health.")

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val SECURITY_CATCHES = setOf("SecurityException", "RuntimeException", "Exception", "Throwable")

    /** The platform's `dangerous` permissions (API 36), the ones a user can deny. */
    private val DANGEROUS: Set<String> = setOf(
        "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION",
        "RECORD_AUDIO", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "USE_SIP", "PROCESS_OUTGOING_CALLS", "ACCEPT_HANDOVER",
        "BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH",
        "RECEIVE_MMS", "READ_CELL_BROADCASTS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
        "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED",
        "ACTIVITY_RECOGNITION", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "UWB_RANGING",
        "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS", "RANGING",
    ).mapTo(HashSet()) { "android.permission.$it" } + "com.android.voicemail.permission.ADD_VOICEMAIL"
}
