package app.apex.tabs

import app.apex.data.UserData
import app.apex.model.Media
import kotlinx.coroutines.flow.StateFlow

/**
 * As abas de vídeo: os vídeos "abertos" agora, como as abas de um navegador. Só um toca por vez (o vídeo da sessão); os outros ficam
 * guardados e voltam de onde pararam (o histórico guarda a posição) ao clicar. A lista sobrevive ao fechar o app.
 *
 * Abrir um vídeo normalmente (clique) troca o vídeo da aba ativa, como um link troca a página da aba do navegador; "Abrir em nova aba"
 * (botão do meio do mouse ou o menu do botão direito) acrescenta uma aba sem interromper o que está tocando.
 */
class VideoTabs(
    private val data: UserData,
    /** O vídeo da sessão agora (a aba ativa é a dele). */
    private val current: () -> Media?,
    /** Começa a preparar um vídeo em segundo plano, para a aba abrir na hora. */
    private val prefetch: (Media) -> Unit,
) {
    val list: StateFlow<List<Media>> get() = data.tabs
    val medias: List<Media> get() = data.tabs.value
    val isFull: Boolean get() = medias.size >= MAX

    /** A chave da aba ativa: a do vídeo da sessão, se ele é uma aba; `null` se nada toca (ou o que toca não é aba). */
    fun activeKey(): String? = current()?.key?.takeIf { key -> medias.any { it.key == key } }

    /** Antes de a sessão abrir [media] por um clique normal: se já é aba, só fica ativa; senão troca o vídeo da aba ativa (ou entra como aba nova). */
    fun opened(media: Media) {
        val list = medias
        if (list.any { it.key == media.key }) return
        val active = activeKey()
        data.setTabs(if (active == null) (list + media).takeLast(MAX) else list.map { if (it.key == active) media else it })
    }

    /** "Abrir em nova aba": entra no fim, sem mexer no que toca, e já começa a preparar. `false` se já estava aberta ou não cabe. */
    fun openInNew(media: Media): Boolean {
        if (medias.any { it.key == media.key } || isFull) return false
        data.setTabs(medias + media)
        prefetch(media)
        return true
    }

    fun close(key: String) = data.setTabs(medias.filterNot { it.key == key })

    /**
     * O vídeo [media] acabou de ser preparado (título, capa, canal de verdade): a aba dele passa a mostrar isso. Um link aberto de uma
     * descrição ou do chat entra como "Carregando…", e sem isto a aba ficava assim para sempre.
     */
    fun refresh(media: Media) {
        val list = medias
        if (list.none { it.key == media.key && it != media }) return
        data.setTabs(list.map { if (it.key == media.key) media else it })
    }

    /** A aba vizinha da ativa ([delta] = +1 a próxima, -1 a anterior), dando a volta; sem aba ativa, a primeira; `null` sem abas. */
    fun neighbor(delta: Int): Media? {
        val list = medias
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.key == activeKey() }
        if (index < 0) return list.first()
        return list[((index + delta) % list.size + list.size) % list.size]
    }

    /** A aba que fica no lugar de [key] quando ela fecha: a da direita, senão a da esquerda; `null` se não há outra. */
    fun neighborOf(key: String): Media? {
        val list = medias
        val index = list.indexOfFirst { it.key == key }
        if (index < 0) return null
        return list.getOrNull(index + 1) ?: list.getOrNull(index - 1)
    }

    /** As abas depois de [media], na ordem: a fila de "tocar em sequência". */
    fun after(media: Media): List<Media> {
        val list = medias
        val index = list.indexOfFirst { it.key == media.key }
        return if (index < 0) emptyList() else list.drop(index + 1)
    }

    /** Leva a aba [key] para a posição [to] (arrastar para reordenar). */
    fun move(key: String, to: Int) {
        val list = medias.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        if (from < 0) return
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        data.setTabs(list)
    }

    companion object {
        /** Abas são só o vídeo e a posição (nenhum player fica aberto), mas a faixa precisa continuar legível. */
        const val MAX = 30
    }
}
