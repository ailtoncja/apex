package app.apex.update

import app.apex.source.AppJson
import app.apex.source.get
import app.apex.source.list
import app.apex.source.str
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive

/** Uma versão nova publicada no GitHub. */
data class UpdateInfo(val version: String, val notes: String, val pageUrl: String)

sealed interface UpdateState {
    /** Ainda não conferiu nesta abertura do app. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState

    /** Baixada e conferida (hash e assinatura): é só reiniciar para instalar. */
    data class Ready(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Atualização do app: confere o GitHub, baixa a versão nova, confere a assinatura e instala. */
interface Updater {
    val currentVersion: String
    val state: StateFlow<UpdateState>

    /** `true` quando o app sabe se instalar sozinho (instalado ou portátil); rodando pelo Gradle só avisa. */
    val canInstall: Boolean

    /** Confere se há versão nova. Com [manual], erros e "já está atualizado" também aparecem. */
    fun check(manual: Boolean = false)

    /** Baixa e confere a versão nova (se ainda não estiver pronta). */
    fun download()

    /** Fecha o app, instala a versão baixada e abre o app de novo. */
    fun installAndRestart()
}

class ReleaseAsset(val name: String, val url: String, val size: Long)

class Release(val version: String, val notes: String, val pageUrl: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

object Versions {
    /** "v1.0.1" → "1.0.1" */
    fun clean(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    private fun parts(version: String): List<Int>? {
        val numeric = clean(version).substringBefore('-').substringBefore('+')
        val list = numeric.split('.').map { it.toIntOrNull() ?: return null }
        return list.takeIf { it.isNotEmpty() }
    }

    /** Negativo se [a] < [b], 0 se iguais, positivo se [a] > [b]. Versão que não dá para ler vale como a mais antiga. */
    fun compare(a: String, b: String): Int {
        val x = parts(a) ?: return if (parts(b) == null) 0 else -1
        val y = parts(b) ?: return 1
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}

/** Lê a resposta de `GET /repos/<dono>/<repo>/releases/latest` da API do GitHub. */
fun parseRelease(json: String): Release? {
    val root: JsonElement = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() ?: return null
    val tag = root["tag_name"].str() ?: return null
    if ((root["draft"] as? JsonPrimitive)?.content == "true" || (root["prerelease"] as? JsonPrimitive)?.content == "true") return null
    val assets = root["assets"].list().mapNotNull { a ->
        ReleaseAsset(
            a["name"].str() ?: return@mapNotNull null,
            a["browser_download_url"].str() ?: return@mapNotNull null,
            (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
    return Release(Versions.clean(tag), root["body"].str().orEmpty(), root["html_url"].str().orEmpty(), assets)
}
