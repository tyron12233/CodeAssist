package dev.ide.android.support.gms

import dev.ide.android.support.AndroidVariant
import dev.ide.model.VariantId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Verifies the [GoogleServices] processor (the on-device google-services Gradle-plugin equivalent) against a
 * realistic `google-services.json`: required + optional resources extracted, client matching (exact / base-
 * namespace fallback / single-client lenience), and the generated `values.xml`. Pure JSON — no SDK.
 */
class GoogleServicesTest {

    private val JSON = """
        {
          "project_info": {
            "project_number": "1234567890",
            "firebase_url": "https://demo-project.firebaseio.com",
            "project_id": "demo-project",
            "storage_bucket": "demo-project.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:1234567890:android:abcdef123456",
                "android_client_info": { "package_name": "com.example.app" }
              },
              "oauth_client": [
                { "client_id": "1234-web.apps.googleusercontent.com", "client_type": 3 },
                { "client_id": "1234-android.apps.googleusercontent.com", "client_type": 1 }
              ],
              "api_key": [ { "current_key": "AIzaSyDEMO0000000000000000000000000000" } ],
              "services": {
                "analytics_service": { "analytics_property": { "tracking_id": "UA-1234-1" } }
              }
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    private fun resources(outcome: GoogleServices.Outcome): Map<String, String> {
        val s = assertIs<GoogleServices.Outcome.Success>(outcome)
        return s.resources.associate { it.name to it.value }
    }

    @Test
    fun extractsRequiredAndOptionalResources() {
        val res = resources(GoogleServices.process(JSON, "com.example.app", "com.example.app"))
        assertEquals("1:1234567890:android:abcdef123456", res["google_app_id"])
        assertEquals("demo-project", res["project_id"])
        assertEquals("1234567890", res["gcm_defaultSenderId"])
        assertEquals("https://demo-project.firebaseio.com", res["firebase_database_url"])
        assertEquals("demo-project.appspot.com", res["google_storage_bucket"])
        assertEquals("AIzaSyDEMO0000000000000000000000000000", res["google_api_key"])
        assertEquals("AIzaSyDEMO0000000000000000000000000000", res["google_crash_reporting_api_key"])
        assertEquals("1234-web.apps.googleusercontent.com", res["default_web_client_id"])
        assertEquals("UA-1234-1", res["ga_trackingId"])
    }

    @Test
    fun fallsBackToBaseNamespaceForSuffixedApplicationId() {
        // applicationIdSuffix=".debug" → com.example.app.debug has no client; falls back to the namespace.
        val outcome = GoogleServices.process(JSON, "com.example.app.debug", "com.example.app")
        val s = assertIs<GoogleServices.Outcome.Success>(outcome)
        assertEquals("com.example.app", s.matchedPackage)
        assertTrue(s.messages.any { "com.example.app.debug" in it })
    }

    @Test
    fun failsWhenNoClientMatches() {
        val outcome = GoogleServices.process(JSON, "com.other.pkg", "com.other.pkg")
        val f = assertIs<GoogleServices.Outcome.Failure>(outcome)
        assertTrue("com.example.app" in f.message, "should list available package names: ${f.message}")
    }

    @Test
    fun valuesXmlIsWellFormedAndMarksNonTranslatable() {
        val res = (GoogleServices.process(JSON, "com.example.app", "com.example.app") as GoogleServices.Outcome.Success).resources
        val xml = GoogleServices.valuesXml(res)
        assertTrue(xml.startsWith("<?xml"))
        assertTrue("<string name=\"google_app_id\" translatable=\"false\">1:1234567890:android:abcdef123456</string>" in xml)
        assertTrue(xml.trimEnd().endsWith("</resources>"))
    }

    @Test
    fun failsOnMissingProjectId() {
        val bad = """{ "project_info": { "project_number": "1" }, "client": [ { "client_info": { "mobilesdk_app_id": "x", "android_client_info": { "package_name": "com.example.app" } } } ] }"""
        val f = assertIs<GoogleServices.Outcome.Failure>(GoogleServices.process(bad, "com.example.app", "com.example.app"))
        assertTrue("project_id" in f.message)
    }

    @Test
    fun locatesJsonByVariantSpecificityThenModuleRoot() {
        val dir = Files.createTempDirectory("gms")
        try {
            val variant = AndroidVariant(VariantId("app:debug"), "debug", "debug", emptyList(), emptyList())
            // Module root first.
            val root = dir.resolve("google-services.json"); Files.writeString(root, "{}")
            assertEquals(root, GoogleServices.findJson(dir, variant))
            // A build-type-specific file wins over the root.
            val btFile = dir.resolve("src/debug/google-services.json")
            Files.createDirectories(btFile.parent); Files.writeString(btFile, "{}")
            assertEquals(btFile, GoogleServices.findJson(dir, variant))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun miniJsonParsesNestedStructuresAndEscapes() {
        val v = MiniJson.parse("""{"a":[1,2.5,true,null,"x\ny"],"b":{"c":"d"}}""")
        @Suppress("UNCHECKED_CAST") val map = v as Map<String, Any?>
        val arr = map["a"] as List<Any?>
        assertEquals(2.5, arr[1])
        assertEquals(true, arr[2])
        assertEquals(null, arr[3])
        assertEquals("x\ny", arr[4])
        assertEquals("d", (map["b"] as Map<*, *>)["c"])
    }
}
