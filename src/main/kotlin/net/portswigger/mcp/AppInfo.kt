package net.portswigger.mcp

import java.util.Properties

/**
 * The extension's version. The value comes from `version` in gradle.properties: the build
 * fills it into `mcp-version.properties`, so there is one place to change it.
 */
object AppInfo {
    private const val VERSION_RESOURCE = "/mcp-version.properties"

    val version: String by lazy {
        AppInfo::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }
            ?.trim()
            // Unfilled placeholder (for example when running from an IDE without Gradle processing).
            ?.takeUnless { it.isBlank() || it.startsWith("\$") }
            ?: "dev"
    }

    /** Name shown in Burp's Extensions list and the MCP tab, for example "Burp MCP Server v1.3.0". */
    val displayName: String get() = "Burp MCP Server v$version"
}
