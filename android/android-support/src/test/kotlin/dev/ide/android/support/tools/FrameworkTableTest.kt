package dev.ide.android.support.tools

import dev.ide.android.support.assumeAndroidSdk
import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The framework table derived for linking ([FrameworkTable]) is the SDK's table with its names in sorted order,
 * so aapt2 binary-searches them. It must change nothing a link produces: the real aapt2, linking real resources
 * that name framework attributes, styles and ids, writes the same bytes against either.
 */
class FrameworkTableTest {

    @Test
    fun aLinkAgainstTheDerivedTableWritesTheSameBytesAsOneAgainstTheSdkJar() = withTempDir("framework-table") { dir ->
        val sdk = assumeAndroidSdk()
        val aapt2 = Aapt2Subprocess(sdk.aapt2)
        dir.writeSource("res/values/values.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Table</string>
                <color name="accent">#ff336699</color>
                <style name="AppTheme" parent="android:Theme.Material.Light">
                    <item name="android:colorAccent">@color/accent</item>
                    <item name="android:windowBackground">@android:color/white</item>
                    <item name="android:textColorPrimary">?android:attr/textColorSecondary</item>
                </style>
                <declare-styleable name="Gauge"><attr name="android:max"/><attr name="needle" format="color"/></declare-styleable>
            </resources>
        """)
        dir.writeSource("res/layout/main.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@android:id/text1" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:text="@string/app_name" android:textAppearance="?android:attr/textAppearanceLarge"
                    android:drawableStart="@android:drawable/ic_menu_search"/>
            </LinearLayout>
        """)
        dir.writeSource("AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.table">
                <application android:label="@string/app_name" android:theme="@style/AppTheme"/>
            </manifest>
        """)
        val compiled = aapt2.compile(listOf(dir.resolve("res")), dir.resolve("compiled"))
        assertTrue(compiled.result.success, "compile failed: ${compiled.result.log}")

        val derived = FrameworkTable.forLinking(sdk.androidJar, dir.resolve("cache"))
        assertTrue(derived != sdk.androidJar && Files.isRegularFile(derived), "a derived table is made: $derived")
        assertEquals(derived, FrameworkTable.forLinking(sdk.androidJar, dir.resolve("cache")), "and reused, not made again")

        fun link(framework: Path, name: String): Pair<ByteArray, String> {
            val ap = dir.resolve("$name.ap_")
            val rTxt = dir.resolve("$name-R.txt")
            val r = aapt2.link(
                compiled.archives, dir.resolve("AndroidManifest.xml"), framework, "com.example.table", emptyList(),
                24, 34, dir.resolve("$name-gen"), ap, rTxt = rTxt,
            )
            assertTrue(r.success, "link against $name failed: ${r.log}")
            return Files.readAllBytes(ap) to Files.readString(rTxt)
        }
        val (plainAp, plainSymbols) = link(sdk.androidJar, "plain")
        val (derivedAp, derivedSymbols) = link(derived, "derived")
        assertEquals(plainSymbols, derivedSymbols, "the same symbols and ids")
        assertTrue(plainAp.contentEquals(derivedAp), "the same resources.ap_, byte for byte (${plainAp.size} vs ${derivedAp.size})")
    }

    @Test
    fun theDerivedTablesNamePoolIsSortedAndTheRewriteIsStable() = withTempDir("framework-table-pool") { dir ->
        val sdk = assumeAndroidSdk()
        val original = ZipFile(sdk.androidJar.toFile()).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
        val sorted = ArscKeyPools.sorted(original)
        assertTrue(sorted.contentEquals(ArscKeyPools.sorted(sorted)), "sorting a sorted table changes nothing")

        // The framework package's key pool: flagged sorted, and its names in UTF-16 code unit order.
        val buf = ByteBuffer.wrap(sorted).order(ByteOrder.LITTLE_ENDIAN)
        var at = buf.getShort(2).toInt() and 0xffff
        while (buf.getShort(at).toInt() and 0xffff != 0x0200) at += buf.getInt(at + 4)
        val pool = at + buf.getInt(at + 8 + 4 + 256 + 8)
        val count = buf.getInt(pool + 8)
        val flags = buf.getInt(pool + 16)
        assertTrue(flags and 1 != 0, "the pool is flagged sorted")
        assertTrue(count > 5_000, "the framework names thousands of resources: $count")
        val stringsStart = buf.getInt(pool + 20)
        val header = buf.getShort(pool + 2).toInt() and 0xffff
        fun name(i: Int): String {
            var p = pool + stringsStart + buf.getInt(pool + header + 4 * i)
            p += if (sorted[p].toInt() and 0x80 != 0) 2 else 1
            val len: Int
            if (sorted[p].toInt() and 0x80 != 0) { len = ((sorted[p].toInt() and 0x7f) shl 8) or (sorted[p + 1].toInt() and 0xff); p += 2 }
            else { len = sorted[p].toInt() and 0xff; p += 1 }
            return String(sorted, p, len, Charsets.UTF_8)
        }
        assertTrue(flags and (1 shl 8) != 0, "this check reads a UTF-8 pool")
        val names = (0 until count).map(::name)
        assertEquals(names.sorted(), names, "the names are in order")
        assertEquals(names.size, names.toSet().size, "and each appears once")
    }
}
