package ru.polytech.jira

import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Writes a self-contained HTML report with six types of SVG charts. */
class ReportWriter {
    /** Creates index.html in the selected output directory. */
    fun write(report: Report, project: String, output: Path, scopeNote: String? = null): Path {
        Files.createDirectories(output)
        val sections = buildString {
            append(section("1. Время от создания до закрытия", "Количество закрытых задач по длительности", histogram(report.openDuration, "Время, ч")))
            append("<section><h2>2. Время по состояниям</h2><p>Только закрытые задачи. Повторные посещения одного состояния суммируются.</p>")
            if (report.statusDurations.isEmpty()) append("<p>Нет данных.</p>")
            report.statusDurations.forEach { (status, bins) ->
                append("<h3>${escape(status)}</h3>").append(histogram(bins, "Время, ч"))
            }
            append("</section>")
            append(section("3. Задачи по дням", "Созданные, закрытые и накопительные итоги", daily(report.daily)))
            append(section("4. Топ 30 пользователей", "Задачи по исполнителю и репортёру; одна задача учитывается в обеих ролях, если пользователь указан дважды", users(report.users)))
            append(section("5. Залогированное время", "Количество закрытых задач по суммарному timeSpent", histogram(report.worklogDuration, "Время, ч")))
            append(section("6. Степень серьёзности", "Количество задач по полю Priority Jira", priorities(report.priorities)))
        }
        val template = javaClass.getResourceAsStream("/report-template.html")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Report template is missing from the application resources")
        val values = mapOf(
            "PROJECT" to escape(project),
            "ISSUE_COUNT" to report.issueCount.toString(),
            "CLOSED_COUNT" to report.closedCount.toString(),
            "SCOPE_NOTE" to (scopeNote?.let { "<p>${escape(it)}</p>" } ?: ""),
            "SECTIONS" to sections
        )
        val html = Regex("""\{\{([A-Z_]+)\}\}""").replace(template) { match ->
            values[match.groupValues[1]] ?: error("Unknown report placeholder ${match.value}")
        }
        val target = output.resolve("index.html")
        Files.writeString(target, html)
        return target
    }

    private fun section(title: String, description: String, content: String) =
        "<section><h2>${escape(title)}</h2><p>${escape(description)}</p>$content</section>"

    private fun histogram(bins: List<HistogramBin>, xLabel: String): String {
        if (bins.isEmpty()) return "<p>Нет данных.</p>"
        val width = 1000.0; val height = 340.0; val left = 70.0; val right = 30.0; val top = 20.0; val bottom = 55.0
        val plotWidth = width - left - right; val plotHeight = height - top - bottom
        val ceiling = max(1, bins.maxOf(HistogramBin::count))
        val barWidth = plotWidth / bins.size
        return buildString {
            append(svgStart(width, height, "Гистограмма по времени"))
            append(axes(left, top, plotWidth, plotHeight, ceiling, "Количество задач"))
            bins.forEachIndexed { index, bin ->
                val barHeight = bin.count * plotHeight / ceiling
                append("<rect x='${f(left + index * barWidth + 1)}' y='${f(top + plotHeight - barHeight)}' width='${f(max(1.0, barWidth - 2))}' height='${f(barHeight)}' fill='#3273b4'><title>${bin.fromHours}–${bin.toHours} ч: ${bin.count}</title></rect>")
                if (index % max(1, bins.size / 8) == 0) append(tick(left + index * barWidth, top + plotHeight + 18, "${bin.fromHours}"))
            }
            append(tick(width / 2, height - 8, xLabel, "label"))
            append("</svg>")
        }
    }

    private fun daily(days: List<DailyCount>): String {
        if (days.isEmpty()) return "<p>Нет данных.</p>"
        val width = 1000.0; val height = 370.0; val left = 70.0; val top = 20.0; val plotWidth = 900.0; val plotHeight = 270.0
        val ceiling = max(1, days.maxOf { maxOf(it.created, it.closed, it.createdTotal, it.closedTotal) })
        val series = listOf(
            Triple("Создано за день", "#277da1", days.map(DailyCount::created)),
            Triple("Закрыто за день", "#e76f51", days.map(DailyCount::closed)),
            Triple("Создано всего", "#2a9d8f", days.map(DailyCount::createdTotal)),
            Triple("Закрыто всего", "#9b5de5", days.map(DailyCount::closedTotal))
        )
        return buildString {
            append(svgStart(width, height, "Созданные и закрытые задачи по календарным дням"))
            append(axes(left, top, plotWidth, plotHeight, ceiling, "Количество задач"))
            series.forEach { (name, color, values) ->
                val points = values.mapIndexed { index, value ->
                    "${f(left + index * plotWidth / max(1, days.size - 1))},${f(top + plotHeight - value * plotHeight / ceiling)}"
                }.joinToString(" ")
                append("<polyline fill='none' stroke='$color' stroke-width='2.5' points='$points'><title>${escape(name)}</title></polyline>")
            }
            days.forEachIndexed { index, day ->
                if (index % max(1, days.size / 8) == 0 || index == days.lastIndex) {
                    append(tick(left + index * plotWidth / max(1, days.size - 1), top + plotHeight + 20, day.day.format(DateTimeFormatter.ISO_DATE)))
                }
            }
            append(tick(width / 2, height - 8, "Календарный день", "label"))
            append("</svg><div class='legend'>")
            series.forEach { (name, color) -> append("<span style='--c:$color'>${escape(name)}</span>") }
            append("</div>")
        }
    }

    private fun users(users: List<UserCount>): String {
        if (users.isEmpty()) return "<p>Нет данных.</p>"
        val width = 1000.0; val left = 260.0; val top = 25.0; val row = 30.0; val plotWidth = 700.0
        val height = top + users.size * row + 55.0
        val ceiling = max(1, users.maxOf(UserCount::total))
        return buildString {
            append(svgStart(width, height, "Топ пользователей по количеству задач"))
            append("<line class='axis' x1='$left' y1='$top' x2='$left' y2='${f(top + users.size * row)}'/>")
            users.forEachIndexed { index, user ->
                val y = top + index * row
                append("<text class='tick' x='${f(left - 8)}' y='${f(y + 19)}' text-anchor='end'>${escape(user.name.take(32))}</text>")
                append("<rect x='$left' y='${f(y + 4)}' width='${f(user.assignee * plotWidth / ceiling)}' height='22' fill='#3273b4'><title>${escape(user.name)}: исполнитель ${user.assignee}</title></rect>")
                append("<rect x='${f(left + user.assignee * plotWidth / ceiling)}' y='${f(y + 4)}' width='${f(user.reporter * plotWidth / ceiling)}' height='22' fill='#e76f51'><title>${escape(user.name)}: репортёр ${user.reporter}</title></rect>")
            }
            append(tick(left + plotWidth / 2, height - 8, "Количество задач", "label"))
            append("</svg><div class='legend'><span style='--c:#3273b4'>Исполнитель</span><span style='--c:#e76f51'>Репортёр</span></div>")
        }
    }

    private fun priorities(priorities: Map<String, Int>): String {
        if (priorities.isEmpty()) return "<p>Нет данных.</p>"
        val items = priorities.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val width = 1000.0; val left = 220.0; val top = 20.0; val row = 35.0; val plotWidth = 740.0
        val height = top + items.size * row + 55.0
        val ceiling = max(1, items.maxOf { it.value })
        return buildString {
            append(svgStart(width, height, "Количество задач по степени серьёзности"))
            items.forEachIndexed { index, item ->
                val y = top + index * row
                append("<text class='tick' x='${f(left - 8)}' y='${f(y + 22)}' text-anchor='end'>${escape(item.key.take(30))}</text>")
                append("<rect x='$left' y='${f(y + 4)}' width='${f(item.value * plotWidth / ceiling)}' height='26' fill='#2a9d8f'><title>${escape(item.key)}: ${item.value}</title></rect>")
                append(tick(left + item.value * plotWidth / ceiling + 6, y + 22, "${item.value}", anchor = "start"))
            }
            append(tick(left + plotWidth / 2, height - 8, "Количество задач", "label"))
            append("</svg>")
        }
    }

    private fun svgStart(width: Double, height: Double, title: String) =
        "<svg xmlns='http://www.w3.org/2000/svg' role='img' aria-label='${escape(title)}' viewBox='0 0 ${f(width)} ${f(height)}'><title>${escape(title)}</title>"

    private fun axes(left: Double, top: Double, width: Double, height: Double, ceiling: Int, yLabel: String): String = buildString {
        for (step in 0..4) {
            val y = top + height - step * height / 4
            append("<line class='grid' x1='${f(left)}' y1='${f(y)}' x2='${f(left + width)}' y2='${f(y)}'/>")
            append(tick(left - 8, y + 4, "${step * ceiling / 4}", anchor = "end"))
        }
        append("<line class='axis' x1='${f(left)}' y1='${f(top)}' x2='${f(left)}' y2='${f(top + height)}'/>")
        append("<line class='axis' x1='${f(left)}' y1='${f(top + height)}' x2='${f(left + width)}' y2='${f(top + height)}'/>")
        append("<text class='label' transform='translate(17 ${f(top + height / 2)}) rotate(-90)' text-anchor='middle'>${escape(yLabel)}</text>")
    }

    private fun tick(x: Double, y: Double, value: String, css: String = "tick", anchor: String = "middle") =
        "<text class='$css' x='${f(x)}' y='${f(y)}' text-anchor='$anchor'>${escape(value)}</text>"
    private fun f(value: Double) = "%.2f".format(java.util.Locale.ROOT, value)
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
