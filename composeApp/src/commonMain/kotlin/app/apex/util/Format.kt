package app.apex.util

import kotlin.math.abs

/** 1234 → "1,2 mil"; 1_500_000 → "1,5 mi". */
fun formatCount(n: Long?): String {
    if (n == null) return ""
    val v = abs(n).toDouble()
    return when {
        v >= 1_000_000_000 -> "${oneDecimal(v / 1_000_000_000)} bi"
        v >= 1_000_000 -> "${oneDecimal(v / 1_000_000)} mi"
        v >= 1_000 -> "${oneDecimal(v / 1_000)} mil"
        else -> n.toString()
    }
}

private fun oneDecimal(v: Double): String {
    val tenths = (v * 10).toLong()
    val whole = tenths / 10
    val frac = tenths % 10
    return if (whole >= 100 || frac == 0L) whole.toString() else "$whole,$frac"
}

fun formatViews(views: Long?, live: Boolean): String = when {
    views == null -> ""
    live -> "${formatCount(views)} assistindo"
    else -> "${formatCount(views)} de visualizações"
}

fun formatDuration(sec: Long?): String {
    if (sec == null || sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

fun formatClock(ms: Long): String = formatDuration((ms / 1000).coerceAtLeast(0)).ifEmpty { "0:00" }

fun relativeTime(epochMs: Long?, nowMs: Long = currentTimeMillis()): String {
    if (epochMs == null || epochMs <= 0) return ""
    val diff = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
    val minute = 60L
    val hour = 3600L
    val day = 86400L
    return when {
        diff < minute -> "agora há pouco"
        diff < hour -> plural(diff / minute, "minuto")
        diff < day -> plural(diff / hour, "hora")
        diff < 30 * day -> plural(diff / day, "dia")
        diff < 365 * day -> plural(diff / (30 * day), "mês", "meses")
        else -> plural(diff / (365 * day), "ano")
    }
}

private fun plural(n: Long, singular: String, plural: String = singular + "s"): String =
    "há $n ${if (n == 1L) singular else plural}"

/** "20240131" → epoch aproximado em ms (meio-dia UTC). */
/** "2026-10-05T19:21:37Z" ou "2026-10-06 11:49:58" (sempre em UTC) → milissegundos desde 1970. */
fun parseIsoMillis(text: String?): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})""").find(text.orEmpty().trim()) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
    return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000L + s.toLong() * 1000L
}

fun parseUploadDate(yyyymmdd: String?): Long? {
    if (yyyymmdd == null || yyyymmdd.length != 8) return null
    val y = yyyymmdd.substring(0, 4).toIntOrNull() ?: return null
    val m = yyyymmdd.substring(4, 6).toIntOrNull() ?: return null
    val d = yyyymmdd.substring(6, 8).toIntOrNull() ?: return null
    return daysFromCivil(y, m, d) * 86_400_000L + 43_200_000L
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

fun formatUploadDate(yyyymmdd: String?): String {
    if (yyyymmdd == null || yyyymmdd.length != 8) return ""
    val months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    val m = yyyymmdd.substring(4, 6).toIntOrNull() ?: return ""
    return "${yyyymmdd.substring(6, 8).trimStart('0')} de ${months.getOrElse(m - 1) { "" }}. de ${yyyymmdd.substring(0, 4)}"
}
