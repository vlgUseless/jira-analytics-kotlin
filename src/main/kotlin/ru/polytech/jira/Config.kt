package ru.polytech.jira

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Properties

data class AppConfig(
    val baseUrl: String,
    val project: String,
    val user: String,
    val token: String,
    val zone: ZoneId,
    val output: Path,
    val binHours: Long,
    val pageSize: Int
) {
    companion object {
        /** Loads and validates a distribution configuration file. Environment credentials take precedence. */
        fun load(path: Path, environment: Map<String, String> = System.getenv()): AppConfig {
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            fun value(key: String) = properties.getProperty(key, "").trim()
            val baseUrl = value("jira.baseUrl").trimEnd('/')
            require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "jira.baseUrl must be an HTTP(S) URL" }
            val project = value("jira.project")
            require(project.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "jira.project must be a Jira project key" }
            val binHours = value("report.binHours").toLongOrNull() ?: 24L
            require(binHours > 0) { "report.binHours must be positive" }
            val pageSize = value("jira.pageSize").toIntOrNull() ?: 100
            require(pageSize in 1..1000) { "jira.pageSize must be between 1 and 1000" }
            val output = Path.of(value("report.output").ifBlank { "report" })
            return AppConfig(
                baseUrl, project,
                environment["JIRA_USER"]?.takeIf(String::isNotBlank) ?: value("jira.user"),
                environment["JIRA_TOKEN"]?.takeIf(String::isNotBlank) ?: value("jira.token"),
                ZoneId.of(value("report.zone").ifBlank { "UTC" }), output, binHours, pageSize
            )
        }
    }
}
