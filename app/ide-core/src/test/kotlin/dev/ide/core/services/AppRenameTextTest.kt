package dev.ide.core.services

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppRenameTextTest {

    @Test
    fun rewritesWholePackageNamesOnly() {
        val text = """
            package com.acme.shop.ui;
            import com.acme.shop.R;
            import com.acme.shopping.Cart;
            String action = "com.acme.shop.ACTION_SYNC";
            String other = "org.com.acme.shop";
        """.trimIndent()
        val out = assertNotNull(AppRenameText.rewritePackage(text, "com.acme.shop", "org.example.store"))
        assertEquals(
            """
            package org.example.store.ui;
            import org.example.store.R;
            import com.acme.shopping.Cart;
            String action = "org.example.store.ACTION_SYNC";
            String other = "org.com.acme.shop";
            """.trimIndent(),
            out,
        )
        assertNull(AppRenameText.rewritePackage("import com.acme.shopping.Cart;", "com.acme.shop", "x.y"))
    }

    @Test
    fun rewritesJniSymbolsInNativeSources() {
        val c = "JNIEXPORT jstring JNICALL Java_com_acme_shop_MainActivity_hello(JNIEnv* env, jobject) {}\n" +
            "void Java_com_acme_shop_1x_Other_run() {}"
        val out = assertNotNull(AppRenameText.rewritePackage(c, "com.acme.shop", "org.my_app", jni = true))
        // `_` in a package is `_1` in the symbol; the `shop_x` package is a different one and stays.
        assertEquals(
            "JNIEXPORT jstring JNICALL Java_org_my_1app_MainActivity_hello(JNIEnv* env, jobject) {}\n" +
                "void Java_com_acme_shop_1x_Other_run() {}",
            out,
        )
    }

    @Test
    fun validatesPackageNames() {
        assertNull(AppRenameText.packageError("org.example.store", needsDot = true))
        assertNotNull(AppRenameText.packageError("store", needsDot = true))
        assertNull(AppRenameText.packageError("store", needsDot = false))
        assertNotNull(AppRenameText.packageError("com.class.app", needsDot = true))
        assertNotNull(AppRenameText.packageError("com..app", needsDot = true))
        assertNotNull(AppRenameText.packageError("com.1app", needsDot = true))
        assertNotNull(AppRenameText.packageError("com.my-app", needsDot = true))
    }

    @Test
    fun readsAndRewritesTheLauncherLabel() {
        val manifest = """<manifest><application android:icon="@mipmap/ic" android:label="@string/app_name"></application></manifest>"""
        assertEquals("@string/app_name", AppRenameText.applicationLabel(manifest))
        assertEquals("app_name", AppRenameText.stringResourceName("@string/app_name"))
        assertNull(AppRenameText.stringResourceName("My App"))

        val literal = """<manifest><application android:label="Old"/></manifest>"""
        // aapt2 reads a bare `"` as a quote toggle, so a literal one keeps its backslash under the XML escape.
        assertEquals(
            """<manifest><application android:label="Bob\'s \&quot;Shop\&quot;"/></manifest>""",
            AppRenameText.withApplicationLabel(literal, "Bob's \"Shop\""),
        )
    }

    @Test
    fun rewritesStringResourcesWithAndroidEscaping() {
        val values = """<resources>
            |    <string name="app_name">Shop</string>
            |    <string name="title" translatable="false">Shop</string>
            |</resources>""".trimMargin()
        val out = assertNotNull(AppRenameText.withStringValue(values, "app_name", "Bob's Shop & Co"))
        assertEquals(true, out.contains("""<string name="app_name">Bob\'s Shop &amp; Co</string>"""))
        assertEquals(true, out.contains("""<string name="title" translatable="false">Shop</string>"""))
        assertEquals("Bob's Shop & Co", AppRenameText.stringValue(out, "app_name"))

        // A translation is only replaced when it is a copy of the old name.
        val es = """<resources><string name="app_name">Tienda</string></resources>"""
        assertNull(AppRenameText.withStringValue(es, "app_name", "Store", onlyIfEquals = "Shop"))
        assertEquals("@home", AppRenameText.decodeString(AppRenameText.encodeString("@home")))
    }
}
