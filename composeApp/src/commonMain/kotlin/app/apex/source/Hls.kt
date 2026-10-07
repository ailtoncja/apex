package app.apex.source

import app.apex.model.Quality

/** Lê a playlist mestra de uma live (Twitch/Kick) e devolve "Automático" + cada qualidade. */
fun parseHlsMaster(masterUrl: String, text: String): List<Quality> {
    val variants = mutableListOf<Quality>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            val attrs = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            if (uri != null) {
                val res = attrs["RESOLUTION"]
                val height = res?.substringAfter('x', "")?.toIntOrNull() ?: 0
                val fps = attrs["FRAME-RATE"]?.toDoubleOrNull()?.toInt()
                val name = attrs["VIDEO"].orEmpty()
                val audioOnly = height == 0 && (name.contains("audio", true) || attrs["CODECS"]?.contains("avc") == false)
                variants += Quality(
                    label = if (audioOnly) "Somente áudio" else friendlyLabel(height, fps, name),
                    height = height,
                    videoUrl = absoluteUrl(masterUrl, uri),
                    fps = fps,
                    audioOnly = audioOnly,
                )
            }
        }
        i++
    }
    val sorted = variants.sortedWith(compareByDescending<Quality> { !it.audioOnly }.thenByDescending { it.height }.thenByDescending { it.fps ?: 0 })
    return listOf(Quality(label = "Automático", height = 0, videoUrl = masterUrl)) + sorted
}

private fun friendlyLabel(height: Int, fps: Int?, name: String): String {
    val base = if (height > 0) "${height}p" else name.ifBlank { "Padrão" }
    val rate = if (fps != null && fps > 30) fps.toString() else ""
    val source = if (name.contains("chunked", true) || name.contains("source", true)) " (Fonte)" else ""
    return "$base$rate$source"
}

private fun parseAttributes(s: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < s.length) {
        val eq = s.indexOf('=', i)
        if (eq < 0) break
        val key = s.substring(i, eq).trim()
        var j = eq + 1
        val value: String
        if (j < s.length && s[j] == '"') {
            val end = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
            value = s.substring(j + 1, end)
            j = end + 1
        } else {
            val end = s.indexOf(',', j).let { if (it < 0) s.length else it }
            value = s.substring(j, end)
            j = end
        }
        out[key] = value
        i = j + 1
    }
    return out
}

private fun absoluteUrl(base: String, ref: String): String {
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    val cut = base.substringBefore('?').substringBeforeLast('/', "")
    return if (ref.startsWith("/")) base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
    else "$cut/$ref"
}
