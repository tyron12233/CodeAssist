// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [AndroidTokenStore] against the real Android Keystore.
 *
 * On a device, because that is the only place this class exists: the keystore, `android.util.Base64` and
 * SharedPreferences are all platform APIs, so a JVM test would be testing a different implementation. The
 * behaviour that matters is the one the sign-in bug was about — a token written by one instance has to be
 * readable by the NEXT one, which is what a relaunch looks like.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTokenStoreTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearStore() {
        AndroidTokenStore(context).write(null)
    }

    @Test
    fun aTokenWrittenNowIsReadableByTheNextInstance() {
        AndroidTokenStore(context).write(REFRESH)

        // A different instance, as a relaunched process would build: nothing carries over in memory.
        assertEquals(REFRESH, AndroidTokenStore(context).read())
    }

    @Test
    fun whatLandsOnDiskIsNotTheToken() {
        AndroidTokenStore(context).write(REFRESH)

        val stored = prefs().getString("refresh_token", null)
        assertTrue("nothing was stored at all", stored != null)
        assertNotEquals("the refresh token is sitting there in plaintext", REFRESH, stored)
        assertTrue("the token appears verbatim inside the stored value", !stored!!.contains(REFRESH))
    }

    @Test
    fun signingOutLeavesNothingBehind() {
        val store = AndroidTokenStore(context)
        store.write(REFRESH)
        store.write(null)

        assertNull(store.read())
        assertNull("the entry has to go, not just read as empty", prefs().getString("refresh_token", null))
    }

    @Test
    fun aValueThatCannotBeDecryptedIsDroppedRatherThanRetried() {
        // What a reinstall or a reset keystore leaves behind: a stored value the current key cannot open.
        prefs().edit().putString("refresh_token", "bm90IGEgcmVhbCB0b2tlbg==").commit()

        assertNull(AndroidTokenStore(context).read())
        assertNull("an unreadable token must be cleared on the failed read", prefs().getString("refresh_token", null))
    }

    @Test
    fun anEmptyStoreReadsAsSignedOut() {
        assertNull(AndroidTokenStore(context).read())
    }

    @Test
    fun aLongTokenSurvivesTheRoundTrip() {
        // Supabase refresh tokens are short, but nothing here should depend on that.
        val long = REFRESH.repeat(200)
        AndroidTokenStore(context).write(long)
        assertEquals(long, AndroidTokenStore(context).read())
    }

    private fun prefs() = context.getSharedPreferences("codeassist.store.auth", Context.MODE_PRIVATE)

    private companion object {
        const val REFRESH = "r-2f7c19ab-not-a-real-token"
    }
}
