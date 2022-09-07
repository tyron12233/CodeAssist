package com.tyron.builder.dexing

import com.android.ide.common.blame.MessageReceiver
import com.tyron.builder.dexing.r8.ClassFileProviderFactory
import java.io.File

/** Parameters required for dexing (with D8). */
class DexParameters(
    val minSdkVersion: Int,
    val debuggable: Boolean,
    val dexPerClass: Boolean,
    val withDesugaring: Boolean,
    val desugarBootclasspath: ClassFileProviderFactory,
    val desugarClasspath: ClassFileProviderFactory,
    val coreLibDesugarConfig: String?,
    val coreLibDesugarOutputKeepRuleFile: File?,
    val messageReceiver: MessageReceiver
)
