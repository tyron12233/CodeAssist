package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class FlutterRunParticipantTest {

    @Test
    fun theLaunchAsksTheDebugEngineForAKnownPortWithNoAuthCode() {
        // FlutterActivity copies these intent extras into the engine's shell arguments; a typo here launches an
        // app whose VM service sits on a random port behind an auth code, and the run waits for it forever.
        val extras = FlutterRunParticipant.extras(40922)
        assertEquals(mapOf("vm-service-port" to 40922, "disable-service-auth-codes" to true), extras)
        assertTrue(extras.values.all { it is Int || it is Boolean }, "extras the installer cannot put in a Bundle")
    }

    @Test
    fun theSessionAttachesToTheServicesHttpRoot() {
        // VmService.connect appends `ws` to the root it is given; handing it the WebSocket URI made it knock on
        // `/ws/ws` and the run stuck at "Connecting to the app".
        val uri = URI(FlutterRunParticipant.serviceUri(40922))
        assertEquals("http", uri.scheme)
        assertEquals(40922, uri.port)
        assertEquals("/", uri.rawPath)
    }

    @Test
    fun onlyDebugVariantsHaveAServiceToAttachTo() {
        assertTrue(FlutterBuildPlugin.isRelease("release"))
        assertTrue(FlutterBuildPlugin.isRelease("freeRelease"))
        assertFalse(FlutterBuildPlugin.isRelease("debug"))
    }
}
