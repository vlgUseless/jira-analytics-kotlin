package ru.polytech.jira

import java.nio.file.Path
import kotlin.system.exitProcess

/** Runs Jira extraction, analysis, and HTML report generation. */
fun main(args: Array<String>) {
    try {
        val config = AppConfig.load(Path.of(args.firstOrNull() ?: "config/app.properties"))
        System.err.println("Jira project ${config.project}: ${if (config.maxIssues == 0) "all issues, ordered by ID" else "up to ${config.maxIssues} issues, created ${config.sortOrder}"}")
        val issues = JiraClient(config).fetchIssues()
        val report = Analyzer(config.zone, config.binHours).analyze(issues)
        val scopeNote = if (config.maxIssues > 0) {
            "Выборка: не более ${config.maxIssues} задач по дате создания (${config.sortOrder}). Граничный день выборки может быть неполным. Для полного отчёта установите jira.maxIssues=0."
        } else null
        val output = ReportWriter().write(report, config.project, config.output, scopeNote)
        println("Processed ${report.issueCount} issues (${report.closedCount} closed). Report: ${output.toAbsolutePath()}")
    } catch (error: Exception) {
        System.err.println("Error: ${error.message}")
        exitProcess(1)
    }
}
