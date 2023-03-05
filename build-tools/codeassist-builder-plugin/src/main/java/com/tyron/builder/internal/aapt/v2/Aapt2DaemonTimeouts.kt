package com.tyron.builder.internal.aapt.v2

import java.util.concurrent.TimeUnit

data class Aapt2DaemonTimeouts(
        val start: Long = 30,
        val startUnit: TimeUnit = TimeUnit.SECONDS,
        val compile: Long = 2,
        val compileUnit: TimeUnit = TimeUnit.MINUTES,
        val link: Long = 10,
        val linkUnit: TimeUnit = TimeUnit.MINUTES,
        val stop: Long = start,
        val stopUnit: TimeUnit = startUnit)