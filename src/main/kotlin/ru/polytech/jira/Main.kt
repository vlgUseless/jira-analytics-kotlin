package ru.polytech.jira

import java.nio.file.Path
import kotlin.system.exitProcess

/** Runs Jira extraction, analysis, and HTML report generation. */
fun main(args: Array<String>) {
    try {
        val config = AppConfig.load(Path.of(args.firstOrNull() ?: "config/app.properties"))
        val issues = JiraClient(config).fetchIssues()
        val report = Analyzer(config.zone, config.binHours).analyze(issues)
        val output = ReportWriter().write(report, config.project, config.output)
        println("Processed ${report.issueCount} issues (${report.closedCount} closed). Report: ${output.toAbsolutePath()}")
    } catch (error: Exception) {
        System.err.println("Error: ${error.message}")
        exitProcess(1)
    }
}
