package net.portswigger.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

class AppInfoTest {

    @Test
    fun `version matches gradle properties`() {
        // Gradle runs tests from the project directory, so this is the same file the build reads.
        val props = Properties().apply { File("gradle.properties").inputStream().use { load(it) } }
        val gradleVersion = props.getProperty("version")
        assertNotNull(gradleVersion, "gradle.properties should define version")

        assertEquals(gradleVersion, AppInfo.version)
    }

    @Test
    fun `display name includes the version`() {
        assertTrue(AppInfo.displayName.endsWith(" v${AppInfo.version}"), AppInfo.displayName)
        assertEquals("Burp MCP Server v${AppInfo.version}", AppInfo.displayName)
    }
}
