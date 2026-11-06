package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.BurpSuite
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.Version
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Checks what an MCP client receives: every tool and every parameter must have a
 * description, and the server must send instructions. Runs with the Professional
 * edition so the scanner and Collaborator tools are registered too.
 */
class ToolDocumentationTest {
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = ServerSocket(0).use { it.localPort }
    private val persistedObject = mockk<PersistedObject>()
    private var serverStarted = false

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    @BeforeEach
    fun setup() {
        val burpSuite = mockk<BurpSuite>(relaxed = true)
        val version = mockk<Version>()
        every { api.burpSuite() } returns burpSuite
        every { burpSuite.version() } returns version
        every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL

        serverManager.start(config) { state ->
            if (state is ServerState.Running) serverStarted = true
        }

        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 30) {
                delay(100)
                attempts++
            }
            check(serverStarted) { "Server failed to start after timeout" }
            client.connectToServer("http://127.0.0.1:$testPort")
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking { if (client.isConnected()) client.close() }
        serverManager.stop {}
    }

    @Test
    fun `every tool has a description`() = runBlocking {
        val tools = client.listTools()
        assertTrue(tools.size > 30, "Expected the full tool set, got ${tools.size}")

        val undocumented = tools.filter { it.description.isNullOrBlank() }.map { it.name }
        assertTrue(undocumented.isEmpty(), "Tools without a description: $undocumented")
    }

    @Test
    fun `every parameter of every tool has a description`() = runBlocking {
        val missing = mutableListOf<String>()

        for (tool in client.listTools()) {
            val properties = tool.inputSchema.properties ?: continue
            for ((name, schema) in properties) {
                val description = schema.jsonObject["description"]?.jsonPrimitive?.content
                if (description.isNullOrBlank()) missing += "${tool.name}.$name"
            }
        }

        assertTrue(missing.isEmpty(), "Parameters without a description: $missing")
    }

    @Test
    fun `server reports the extension version`() = runBlocking {
        client.listTools()

        assertEquals(AppInfo.version, client.serverVersion(), "Server should report the extension version")
        assertTrue(AppInfo.version.matches(Regex("""\d+\.\d+\.\d+""")), "Unexpected version: ${AppInfo.version}")
    }

    @Test
    fun `server sends instructions that guide history paging`() = runBlocking {
        client.listTools()

        val instructions = client.serverInstructions().orEmpty()
        assertTrue(instructions.isNotBlank(), "Server should send instructions")
        assertTrue(instructions.contains("get_proxy_history_count"), "Instructions should mention the count tool")
        assertTrue(instructions.contains("nextOffset"), "Instructions should explain paging with nextOffset")
    }
}
