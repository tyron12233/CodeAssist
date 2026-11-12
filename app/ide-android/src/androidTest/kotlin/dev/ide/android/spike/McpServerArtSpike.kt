package dev.ide.android.spike

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.agent.mcp.CodeAssistMcpServer
import dev.ide.agent.mcp.FileSystemAgentWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * The in-app MCP server starts and answers on ART. The MCP SDK finds its JSON mapper through
 * `ServiceLoader.findFirst()`, which Android has only from API 34: below that, starting the server threw a
 * `NoSuchMethodError` out of the agent backend's constructor and the IDE never got past "Failed to start".
 * The server is built here exactly as the backend builds it, then asked to initialize and to list its tools.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.McpServerArtSpike
 */
@RunWith(AndroidJUnit4::class)
class McpServerArtSpike {

    @Test
    fun theServerStartsAndAnswers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val project = context.cacheDir.toPath().resolve("mcp-spike").also { it.toFile().deleteRecursively(); Files.createDirectories(it) }
        Files.write(project.resolve("Main.kt"), "fun main() {}\n".toByteArray())

        val server = CodeAssistMcpServer.startHttpServer(FileSystemAgentWorkspace(project), port = 0)
        try {
            val (status, session, body) = post(
                server.port, null,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"spike","version":"1"}}}""",
            )
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} initialize: $status ${body.take(300)}")
            assertEquals(body, 200, status)
            assertTrue(body, CodeAssistMcpServer.DEFAULT_SERVER_NAME in body)

            post(server.port, session, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            val (listStatus, _, tools) = post(server.port, session, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            Log.i(TAG, "tools/list: $listStatus ${tools.take(200)}")
            assertEquals(tools, 200, listStatus)
            assertTrue(tools, "read_file" in tools)
        } finally {
            server.close()
        }
    }

    private fun post(port: Int, session: String?, json: String): Triple<Int, String?, String> {
        val connection = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        session?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
        connection.outputStream.use { it.write(json.toByteArray()) }
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return Triple(status, connection.getHeaderField("Mcp-Session-Id"), body)
    }

    private companion object {
        const val TAG = "McpServerArt"
    }
}
