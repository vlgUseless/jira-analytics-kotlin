package ru.polytech.jira

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigTest {
    @TempDir lateinit var directory: Path

    @Test fun `environment credentials override file and token is redacted`() {
        val path = directory.resolve("app.properties")
        Files.writeString(path, "jira.baseUrl=https://jira.example.org/jira\njira.project=TEST\njira.user=file-user\njira.token=file-token\n")
        val config = AppConfig.load(path, mapOf("JIRA_USER" to "env-user", "JIRA_TOKEN" to "env-secret"))
        assertEquals("env-user", config.user)
        assertEquals("env-secret", config.token)
        assertFalse(config.toString().contains("env-secret"))
    }

    @Test fun `invalid base URL and issue limit are rejected`() {
        val path = directory.resolve("app.properties")
        Files.writeString(path, "jira.baseUrl=ftp://jira.example.org\njira.project=TEST\n")
        assertFailsWith<IllegalArgumentException> { AppConfig.load(path, emptyMap()) }
        Files.writeString(path, "jira.baseUrl=https://jira.example.org\njira.project=TEST\njira.maxIssues=-1\n")
        assertFailsWith<IllegalArgumentException> { AppConfig.load(path, emptyMap()) }
    }
}
