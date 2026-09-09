package dev.ide.android.support.manifest

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidVariant
import dev.ide.android.support.AndroidVariants
import dev.ide.android.support.BuildType
import dev.ide.android.support.ProductFlavor
import dev.ide.model.VariantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Manifest placeholders a module declares, and the dependency manifests that need them.
 *
 * Reported against 3.9.9: `com.github.myketstore:myket-billing-client` ships a manifest naming
 * `${marketPermission}`, `${marketApplicationId}`, and `${marketBindAddress}`. The setup its docs give is
 * `defaultConfig { manifestPlaceholders = [...] }`, which the model did not carry at all, so the values could
 * never reach the merge: every placeholder stayed unresolved and aapt2 rejected the merged manifest. The merger
 * always substituted whatever it was handed; what was missing was anything to hand it beyond the build's own
 * `applicationId`/`packageName`.
 */
class ManifestPlaceholderTest {

    private fun variant(buildType: String = "debug", flavors: List<String> = emptyList()) =
        AndroidVariant(VariantId(buildType), buildType, buildType, flavors, emptyList())

    // ---- the effective map -------------------------------------------------------------------------

    @Test fun theBuildsOwnPlaceholdersAreAlwaysAvailable() {
        val facet = AndroidFacet(namespace = "com.example.app", compileSdk = 34)
        val values = AndroidVariants.manifestPlaceholders(facet, variant())

        assertEquals("com.example.app", values["applicationId"])
        assertEquals("com.example.app", values["packageName"])
    }

    @Test fun declaredPlaceholdersReachTheMapInAgpPrecedenceOrder() {
        // defaultConfig < flavor < build type, each layer overriding only the keys it names.
        val facet = AndroidFacet(
            namespace = "com.example.app",
            compileSdk = 34,
            manifestPlaceholders = mapOf(
                "marketApplicationId" to "ir.mservices.market",
                "marketPermission" to "ir.mservices.market.BILLING",
                "host" to "default",
            ),
            flavorDimensions = listOf("tier"),
            productFlavors = listOf(
                ProductFlavor("free", dimension = "tier", manifestPlaceholders = mapOf("host" to "free-host")),
            ),
            buildTypes = listOf(
                BuildType("debug", manifestPlaceholders = mapOf("endpoint" to "staging")),
                BuildType("release"),
            ),
        )

        val debug = AndroidVariants.manifestPlaceholders(facet, variant("debug", listOf("free")))
        assertEquals("ir.mservices.market", debug["marketApplicationId"], "a defaultConfig value must survive")
        assertEquals("free-host", debug["host"], "the flavor overrides defaultConfig")
        assertEquals("staging", debug["endpoint"], "the build type contributes its own")

        val release = AndroidVariants.manifestPlaceholders(facet, variant("release", listOf("free")))
        assertEquals(null, release["endpoint"], "another build type's placeholder must not leak in")
        assertEquals("default", AndroidVariants.manifestPlaceholders(facet, variant("release")).getValue("host"))
    }

    @Test fun aDeclaredApplicationIdPlaceholderOverridesTheInjectedOne() {
        // AGP resolves ${applicationId} from the same map, so a module that declares the key wins.
        val facet = AndroidFacet(
            namespace = "com.example.app", compileSdk = 34,
            manifestPlaceholders = mapOf("applicationId" to "com.example.override"),
        )
        assertEquals("com.example.override", AndroidVariants.manifestPlaceholders(facet, variant())["applicationId"])
    }

    // ---- the reported dependency ------------------------------------------------------------------

    @Test fun aDependencyManifestNeedingPlaceholdersMergesWhenTheModuleDeclaresThem() {
        val facet = AndroidFacet(
            namespace = "com.example.app",
            compileSdk = 34,
            manifestPlaceholders = mapOf(
                "marketApplicationId" to "ir.mservices.market",
                "marketPermission" to "ir.mservices.market.BILLING",
                "marketBindAddress" to "ir.mservices.market.InAppBillingService.BIND",
            ),
        )
        val r = ManifestMerger.mergeXml(APP, listOf(BILLING_LIB), AndroidVariants.manifestPlaceholders(facet, variant()))

        assertFalse(r.hasErrors, "the declared placeholders must resolve the dependency's manifest: ${r.messages}")
        assertTrue(r.messages.none { "placeholder" in it.text }, "nothing should be reported unresolved: ${r.messages}")
        assertTrue("\${" !in r.xml, "no placeholder may survive into the merged manifest:\n${r.xml}")
        assertTrue("ir.mservices.market.BILLING" in r.xml, "the permission's value must be substituted:\n${r.xml}")
        assertTrue("ir.mservices.market.InAppBillingService.BIND" in r.xml, "…and the bind address:\n${r.xml}")
    }

    @Test fun withoutThemTheMergeStillFailsButNamesWhatIsMissing() {
        // The honest outcome when the project never declares them (AGP fails here too): an error the user can
        // act on, naming the placeholder rather than letting aapt2 fail on a line number.
        val facet = AndroidFacet(namespace = "com.example.app", compileSdk = 34)
        val r = ManifestMerger.mergeXml(APP, listOf(BILLING_LIB), AndroidVariants.manifestPlaceholders(facet, variant()))

        assertTrue(r.hasErrors, "an unresolved placeholder in an android:name is an aapt2 failure")
        assertTrue(
            r.messages.any { it.severity == ManifestMerger.Severity.ERROR && "marketPermission" in it.text },
            "the error must name the placeholder that has no value: ${r.messages}",
        )
    }

    private companion object {
        val APP = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="App"/>
            </manifest>
        """.trimIndent()

        /** The shape of the reported dependency's manifest: identifiers and values built from placeholders. */
        val BILLING_LIB = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ir.mservices.billing">
                <uses-permission android:name="${'$'}{marketPermission}"/>
                <queries>
                    <package android:name="${'$'}{marketApplicationId}"/>
                </queries>
                <application>
                    <meta-data android:name="market.applicationId" android:value="${'$'}{marketApplicationId}"/>
                    <meta-data android:name="market.bindAddress" android:value="${'$'}{marketBindAddress}"/>
                </application>
            </manifest>
        """.trimIndent()
    }
}
