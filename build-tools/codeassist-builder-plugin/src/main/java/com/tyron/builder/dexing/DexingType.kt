package com.tyron.builder.dexing

/**
 * The type of dex we produce. It can be:
 *
 *
 *  * mono dex: no multidex enabled, only one final DEX file produced
 *  * legacy multidex: multidex enabled, and min sdk version is less than 21
 *  * native multidex: multidex enabled, and min sdk version is greater or equal to 21
 *
 */
enum class DexingType(
    /** If this mode allows multiple DEX files.  */
    val multiDex: Boolean,
    /** If we should pre-dex in this dexing mode.  */
    val preDex: Boolean,
    /** If a main dex list is required for this dexing mode.  */
    val needsMainDexList: Boolean
) {
    MONO_DEX(
        multiDex = false,
        preDex = true,
        needsMainDexList = false
    ),
    LEGACY_MULTIDEX(
        multiDex = true,
        preDex = false,
        needsMainDexList = true
    ),
    NATIVE_MULTIDEX(
        multiDex = true,
        preDex = true,
        needsMainDexList = false
    );

    fun isPreDex() = preDex
    fun isMultiDex() = multiDex
}

fun DexingType.isLegacyMultiDexMode() = this === DexingType.LEGACY_MULTIDEX