// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnrTraceTest {

    /** The shape ART writes (`Thread::Dump`, `Monitor::DescribeWait`), trimmed to the lines that matter. */
    private val dump = """
        ----- pid 12345 at 2026-10-18 10:00:00.123456789+0800 -----
        Cmd line: com.tyron.code
        Build fingerprint: 'samsung/a16/a16:15/AP3A/1:user/release-keys'
        ABI: 'arm64'

        suspend all histogram:	Sum: 1.2ms 99% C.I. 2us-200us Avg: 20us Max: 300us
        DALVIK THREADS (4):
        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a1b2c8 self=0xb400007a1c2d3e40
          | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b8c9d0e10
          | state=S schedstat=( 1 2 3 ) utm=100 stm=20 core=4 HZ=100
          | stack=0x7fc0000000-0x7fc0002000 stackSize=8188KB
          | held mutexes=
          at dev.ide.core.IdeServices.save(IdeServices.kt:1330)
          - waiting to lock <0x0fd2a3b1> (a java.lang.Object) held by thread 23
          at dev.ide.ui.editor.EditorBackend.saveFile(EditorBackend.kt:181)
          at com.example.userapp.Secret.method(Secret.java:9)
          at com.example.userapp.Other.run(Other.java:3)
          at androidx.compose.runtime.Recomposer.runRecomposeAndApplyChanges(Recomposer.kt:700)
          at android.os.Looper.loop(Looper.java:317)
          native: #00 pc 00000000000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28) (BuildId: abc123)
          native: #01 pc 0000000000234567  /apex/com.android.art/lib64/libart.so (art::Monitor::Lock(art::Thread*)+1024) (BuildId: def)

        "Signal Catcher" daemon prio=10 tid=6 Runnable
          | group="system" sCount=0 ucsCount=0 flags=0 obj=0x13c00258 self=0xb400007a1c2d4f50
          native: #00 pc 00000000005f1e2c  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+108)

        "ide-engine" prio=5 tid=23 Runnable
          | group="main" sCount=0 ucsCount=0 flags=0 obj=0x13c00300 self=0xb400007a1c2d5060
          at it.unimi.dsi.fastutil.ints.IntOpenHashSet.contains(IntOpenHashSet.java:250)
          at com.intellij.lang.impl.PsiBuilderImpl.buildTree(PsiBuilderImpl.java:1200)
          - locked <0x0fd2a3b1> (a java.lang.Object)
          at dev.ide.lang.java.JavaAnalyzer.analyze(JavaAnalyzer.kt:88)
          at java.lang.Thread.run(Thread.java:1012)

        "binder:12345_1" prio=5 (not attached)
          native: #00 pc 00000000000a9b8c  /apex/com.android.runtime/lib64/bionic/libc.so (__ioctl+12)

        ----- end 12345 -----

        ----- pid 999 at 2026-10-18 10:00:00 -----
        "main" prio=5 tid=1 Native
          at com.other.App.main(App.java:1)
    """.trimIndent()

    @Test
    fun `reads the main thread and the thread holding its lock`() {
        val t = assertNotNull(AnrTrace.parse(dump))
        val main = assertNotNull(t.main)
        assertEquals("main", main.name)
        assertEquals("Blocked", main.state)
        assertEquals("dev.ide.core.IdeServices.save:1330", main.frames.first())
        assertEquals("dev.ide.core.IdeServices.save:1330", main.firstOwnFrame)

        val holder = assertNotNull(t.lockHolder)
        assertEquals("ide-engine", holder.name)
        assertEquals("Runnable", holder.state)
        assertEquals(
            listOf(
                "it.unimi.dsi.fastutil.ints.IntOpenHashSet.contains:250",
                "com.intellij.lang.impl.PsiBuilderImpl.buildTree:1200",
                "dev.ide.lang.java.JavaAnalyzer.analyze:88",
                "java.lang.Thread.run:1012",
            ),
            holder.frames,
        )
        // Only the first process's threads count; the unattached binder thread ends a block but is not one.
        assertEquals(3, t.threadCount)
    }

    @Test
    fun `frames of code outside the safe packages collapse to a count`() {
        val main = assertNotNull(AnrTrace.parse(dump)?.main)
        assertEquals(
            listOf(
                "dev.ide.core.IdeServices.save:1330",
                "dev.ide.ui.editor.EditorBackend.saveFile:181",
                "… (2 frames)",
                "androidx.compose.runtime.Recomposer.runRecomposeAndApplyChanges:700",
                "android.os.Looper.loop:317",
            ),
            main.frames,
        )
        assertFalse(main.frames.any { "userapp" in it }, main.frames.toString())
    }

    @Test
    fun `native frames keep the library basename and the symbol only`() {
        val main = assertNotNull(AnrTrace.parse(dump)?.main)
        assertEquals(listOf("libc.so!syscall", "libart.so!art::Monitor::Lock(art::Thread*)"), main.nativeFrames)
        assertFalse(main.nativeFrames.any { '/' in it || "BuildId" in it })
    }

    @Test
    fun `a main thread that is not blocked on a monitor has no lock holder`() {
        val idle = """
            "main" prio=5 tid=1 Native
              at android.os.MessageQueue.nativePollOnce(Native method)
              at android.os.MessageQueue.next(MessageQueue.java:335)
              - waiting on <0x01> (a java.lang.Object)
        """.trimIndent()
        val t = assertNotNull(AnrTrace.parse(idle))
        assertEquals("android.os.MessageQueue.nativePollOnce:-2", t.main?.frames?.first())
        assertNull(t.main?.firstOwnFrame)
        assertNull(t.lockHolder)
    }

    @Test
    fun `the own frame is found past the frame cap`() {
        val deep = buildString {
            appendLine("\"main\" prio=5 tid=1 Runnable")
            repeat(40) { appendLine("  at androidx.compose.ui.Node.f$it(Node.kt:$it)") }
            appendLine("  at dev.ide.ui.Editor.draw(Editor.kt:77)")
        }
        val main = assertNotNull(AnrTrace.parse(deep)?.main)
        assertEquals(AnrTrace.MAX_FRAMES, main.frames.size)
        assertEquals("dev.ide.ui.Editor.draw:77", main.firstOwnFrame)
    }

    @Test
    fun `a gzipped stream is inflated, and garbage yields null`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(dump.toByteArray()) } }
        val t = assertNotNull(AnrTrace.parse(ByteArrayInputStream(gz.toByteArray())))
        assertEquals("Blocked", t.main?.state)

        assertNull(AnrTrace.parse(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
        assertNull(AnrTrace.parse(""))
    }

    @Test
    fun `a dump cut short reports what it read`() {
        val cut = dump.substring(0, dump.indexOf("at com.example.userapp.Other"))
        val main = assertNotNull(AnrTrace.parse(cut)?.main)
        assertEquals("Blocked", main.state)
        assertTrue(main.frames.size >= 2, main.frames.toString())
    }

    @Test
    fun `the description maps to a coarse kind and a background flag`() {
        assertEquals("input", AnrTrace.kindOf("user request after error: Input dispatching timed out (abc com.tyron.code/x is not responding. Waited 5001ms for MotionEvent)"))
        assertEquals("broadcast", AnrTrace.kindOf("Broadcast of Intent { act=android.intent.action.SCREEN_OFF }"))
        assertEquals("service", AnrTrace.kindOf("executing service com.tyron.code/.BuildService"))
        assertEquals("foreground_service", AnrTrace.kindOf("Context.startForegroundService() did not then call Service.startForeground()"))
        assertEquals("provider", AnrTrace.kindOf("ContentProvider not responding"))
        assertEquals("other", AnrTrace.kindOf("App requested"))
        assertEquals("unknown", AnrTrace.kindOf(null))

        assertTrue(AnrTrace.isBackground("bg anr: Input dispatching timed out"))
        assertFalse(AnrTrace.isBackground("Input dispatching timed out"))
        assertFalse(AnrTrace.isBackground(null))
    }
}
