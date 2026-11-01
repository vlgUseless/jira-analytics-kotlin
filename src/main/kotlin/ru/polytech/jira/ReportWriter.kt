package ru.polytech.jira

import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** Writes a self-contained HTML report with six types of SVG charts. */
class ReportWriter {
    /** Creates index.html in the selected output directory. */
    fun write(report: Report, project: String, output: Path, scopeNote: String? = null): Path {
        Files.createDirectories(output)
        val sections = buildString {
            append(section("1. Время от создания до закрытия", "Сколько времени прошло от создания задачи до даты её разрешения (resolutiondate). Учитываются только закрытые задачи.", histogram(report.openDuration, "Длительность", 24)))
            append("<section><h2>2. Время по состояниям</h2><p>Сколько времени закрытая задача провела в каждом статусе до даты разрешения. Переходы берутся из истории Jira; повторные посещения одного статуса суммируются. Одна задача может входить в несколько диаграмм.</p>")
            if (report.statusDurations.isEmpty()) append("<p>Нет данных.</p>")
            report.statusDurations.forEach { (status, bins) ->
                val count = bins.sumOf(HistogramBin::count)
                append("<h3>${escape(status)} — $count ${taskWord(count)}</h3>")
                if (count <= 1) append("<p>Для этого статуса в выборке слишком мало задач, поэтому форма распределения не показательная.</p>")
                append(histogram(bins, "Время в статусе", 24))
            }
            append("</section>")
            append(section("3. Задачи по дням", "«Создано» — по дате created, «закрыто» — по дате resolutiondate. Дневные события и накопительные итоги показаны на отдельных шкалах.", daily(report.daily)))
            append(section("4. Топ 30 пользователей", "Задачи по исполнителю и репортёру; одна задача учитывается в обеих ролях, если пользователь указан дважды", users(report.users)))
            val logged = report.closedCount - report.unloggedClosedCount
            val worklogContent = "<p>Суммарное поле timeSpent одной закрытой задачи. С записанным временем: $logged; без него: ${report.unloggedClosedCount}. Время отдельных пользователей здесь не разделяется.</p>" +
                (if (logged == 1) "<p>Запись времени есть только у одной задачи, поэтому форма распределения не показательна.</p>" else "") +
                if (logged == 0) "<p>Для этой выборки Jira не вернула положительного залогированного времени. Распределение по времени построить не из чего.</p>"
                else histogram(report.worklogDuration, "Залогировано", 1)
            append(section("5. Залогированное время", "Распределение закрытых задач с положительным залогированным временем.", worklogContent))
            append(section("6. Степень серьёзности", "Количество всех полученных задач по полю Priority Jira. Стандартные приоритеты показаны от наиболее серьёзного к наименее серьёзному.", priorities(report.priorities)))
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

    private fun histogram(bins: List<HistogramBin>, xLabel: String, preferredUnitHours: Long): String {
        if (bins.isEmpty()) return "<p>Нет данных.</p>"
        val width = 1000.0; val height = 390.0; val left = 70.0; val right = 30.0; val top = 20.0; val bottom = 105.0
        val plotWidth = width - left - right; val plotHeight = height - top - bottom
        val ceiling = max(1, bins.maxOf(HistogramBin::count))
        val barWidth = plotWidth / bins.size
        val unitHours = if (preferredUnitHours == 24L && (bins.first().toHours - bins.first().fromHours) % 24 == 0L) 24L else 1L
        val unitName = if (unitHours == 24L) "дни" else "часы"
        return buildString {
            val regular = bins.first()
            val binSize = (regular.toHours - regular.fromHours) / unitHours
            append("<p>Ширина обычного интервала: $binSize ${if (unitHours == 24L) "дн." else "ч."} Числа на оси — границы интервалов.</p>")
            append(svgStart(width, height, "Гистограмма по времени"))
            append(axes(left, top, plotWidth, plotHeight, ceiling, "Количество задач"))
            bins.forEachIndexed { index, bin ->
                val barHeight = bin.count * plotHeight / ceiling
                val from = bin.fromHours / unitHours
                val to = bin.toHours / unitHours
                val unit = if (unitHours == 24L) "д" else "ч"
                val range = if (bin.overflow) "≥$from $unit" else "$from–$to $unit"
                append("<rect x='${f(left + index * barWidth + 1)}' y='${f(top + plotHeight - barHeight)}' width='${f(max(1.0, barWidth - 2))}' height='${f(barHeight)}' fill='${if (bin.overflow) "#d97706" else "#3273b4"}'><title>$range: ${bin.count} ${taskWord(bin.count)}</title></rect>")
                append(histogramBoundary(left + index * barWidth, top + plotHeight, if (bin.overflow) "≥$from" else "$from"))
            }
            if (!bins.last().overflow) append(histogramBoundary(left + plotWidth, top + plotHeight, "${bins.last().toHours / unitHours}"))
            append(tick(width / 2, height - 8, "$xLabel, $unitName", "label"))
            append("</svg>")
            bins.lastOrNull()?.takeIf(HistogramBin::overflow)?.let { overflow ->
                val unit = if (unitHours == 24L) "дней" else "часов"
                append("<p>Последний столбец объединяет все задачи с длительностью от ${overflow.fromHours / unitHours} $unit. Граница выбрана по 95-му процентилю длительностей и округлена до ширины интервала.</p>")
            }
        }
    }

    private fun daily(days: List<DailyCount>): String {
        if (days.isEmpty()) return "<p>Нет данных.</p>"
        val from = days.first().day.format(DateTimeFormatter.ISO_DATE)
        val to = days.last().day.format(DateTimeFormatter.ISO_DATE)
        return buildString {
            append("<p>Период: $from — $to (${days.size} календарных дней). Начало — самая ранняя дата создания среди загруженных задач; конец — самая поздняя дата создания или разрешения. Дни без событий включены.</p>")
            append(dailyPanel(days, "Создано и закрыто за день", "Задач за день", listOf(
                Triple("Создано за день", "#277da1", days.map(DailyCount::created)),
                Triple("Закрыто за день", "#e76f51", days.map(DailyCount::closed))
            )))
            append(dailyPanel(days, "Накопительный итог", "Задач с начала периода", listOf(
                Triple("Создано всего", "#2a9d8f", days.map(DailyCount::createdTotal)),
                Triple("Закрыто всего", "#9b5de5", days.map(DailyCount::closedTotal))
            )))
        }
    }

    private fun dailyPanel(days: List<DailyCount>, title: String, yLabel: String,
                           series: List<Triple<String, String, List<Int>>>): String {
        val width = 1000.0; val height = 370.0; val left = 70.0; val top = 20.0; val plotWidth = 900.0; val plotHeight = 270.0
        val ceiling = max(1, series.maxOf { it.third.maxOrNull() ?: 0 })
        return buildString {
            append("<div class='chart-panel'><h3>${escape(title)}</h3>")
            append(svgStart(width, height, title))
            append(axes(left, top, plotWidth, plotHeight, ceiling, yLabel))
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
            append("</div></div>")
        }
    }

    private fun users(users: List<UserCount>): String {
        if (users.isEmpty()) return "<p>Нет данных.</p>"
        val width = 1000.0; val left = 260.0; val top = 25.0; val row = 30.0; val plotWidth = 650.0
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
                append(tick(left + user.total * plotWidth / ceiling + 7, y + 20, "${user.total}", anchor = "start"))
            }
            append(tick(left + plotWidth / 2, height - 8, "Количество задач", "label"))
            append("</svg><div class='legend'><span style='--c:#3273b4'>Исполнитель</span><span style='--c:#e76f51'>Репортёр</span></div>")
        }
    }

    private fun priorities(priorities: Map<String, Int>): String {
        if (priorities.isEmpty()) return "<p>Нет данных.</p>"
        val severityOrder = listOf("Blocker", "Critical", "Major", "Minor", "Trivial",
            "Highest", "High", "Medium", "Low", "Lowest")
        val items = priorities.entries.sortedWith(compareBy<Map.Entry<String, Int>> {
            severityOrder.indexOf(it.key).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.key })
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
        val steps = minOf(4, ceiling)
        val values = (0..steps).map { (it * ceiling.toDouble() / steps).roundToInt() }.distinct()
        for (value in values) {
            val y = top + height - value * height / ceiling
            append("<line class='grid' x1='${f(left)}' y1='${f(y)}' x2='${f(left + width)}' y2='${f(y)}'/>")
            append(tick(left - 8, y + 4, "$value", anchor = "end"))
        }
        append("<line class='axis' x1='${f(left)}' y1='${f(top)}' x2='${f(left)}' y2='${f(top + height)}'/>")
        append("<line class='axis' x1='${f(left)}' y1='${f(top + height)}' x2='${f(left + width)}' y2='${f(top + height)}'/>")
        append("<text class='label' transform='translate(17 ${f(top + height / 2)}) rotate(-90)' text-anchor='middle'>${escape(yLabel)}</text>")
    }

    private fun tick(x: Double, y: Double, value: String, css: String = "tick", anchor: String = "middle") =
        "<text class='$css' x='${f(x)}' y='${f(y)}' text-anchor='$anchor'>${escape(value)}</text>"
    private fun histogramBoundary(x: Double, baseline: Double, label: String): String {
        val y = baseline + 16
        return "<line class='axis' x1='${f(x)}' y1='${f(baseline)}' x2='${f(x)}' y2='${f(baseline + 6)}'/>" +
            "<text class='tick' x='${f(x)}' y='${f(y)}' text-anchor='end' transform='rotate(-55 ${f(x)} ${f(y)})'>${escape(label)}</text>"
    }
    private fun f(value: Double) = "%.2f".format(java.util.Locale.ROOT, value)
    private fun taskWord(count: Int) = when {
        count % 100 in 11..14 -> "задач"
        count % 10 == 1 -> "задача"
        count % 10 in 2..4 -> "задачи"
        else -> "задач"
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
