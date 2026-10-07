package app.apex

import app.apex.data.KeyValueStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.SupportKind
import app.apex.source.AppJson
import app.apex.source.parseActiveMemberships
import app.apex.source.parseJson
import app.apex.util.ImagePrefetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canais que a pessoa paga (sub da Twitch, membro do YouTube) e as imagens que as listas baixam antes de aparecer. */
class SupportTest {
    private class MemoryStore : KeyValueStore {
        private val map = ConcurrentHashMap<String, String>()
        override fun read(name: String): String? = map[name]
        override fun write(name: String, text: String) {
            map[name] = text
        }
    }

    private fun data() = UserData(MemoryStore(), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private fun yt(id: String, name: String, avatar: String? = null) = Channel(Platform.YouTube, id, name, avatar)

    private fun card(title: String, image: String?, vararg more: String): String {
        val texts = (listOf(title) + more).joinToString(",") { """{"cardItemTextRenderer":{"text":{"runs":[{"text":"$it"}]}}}""" }
        val img = image?.let { """"imageRenderer":{"themedImageRenderer":{"imageLight":{"thumbnails":[{"url":"$it"},{"url":"$it-grande"}]}}},""" }.orEmpty()
        return """{"cardItemRenderer":{"headingRenderer":{"cardItemTextWithImageRenderer":{$img"textCollectionRenderer":[{"cardItemTextCollectionRenderer":{"textRenderers":[$texts]}}]}}}}"""
    }

    private fun section(title: String) =
        """{"cardItemRenderer":{"headingRenderer":{"cardItemTextCollectionRenderer":{"textRenderers":[{"cardItemTextRenderer":{"text":{"runs":[{"text":"$title"}]}}}]}}}}"""

    private fun page(vararg cards: String) =
        parseJson("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[${cards.joinToString(",")}]}}]}}}}]}}}""")!!

    // ---------------------------------------------------------------- YouTube

    @Test
    fun membro_ativo_e_achado_pela_foto_ou_pelo_nome_e_inativo_nao_conta() {
        val known = listOf(
            yt("UC1", "Canal Ativo", "https://yt3.ggpht.com/AAA=s68-c-k-c0x00ffffff-no-rj"),
            yt("UC2", "Outro Nome Aqui", "https://yt3.ggpht.com/BBB=s68-c-k-c0x00ffffff-no-rj"),
            yt("UC3", "Canal   Antigo", "https://yt3.ggpht.com/CCC=s68-c-k-c0x00ffffff-no-rj"),
            yt("UC4", "Nunca Foi Membro", "https://yt3.ggpht.com/DDD=s68-c-k-c0x00ffffff-no-rj"),
        )
        val root = page(
            section("Compras e locações digitais"),
            section("Assinaturas"),
            card("YouTube Premium", "https://www.gstatic.com/youtube/img/unlimited/premium.png", "Assinatura de estudante: "),
            card("Canal Ativo", "https://yt3.ggpht.com/AAA=s48-c-k", "Nível Ouro", "Renova em 10 de nov."),
            // A foto é outra (o canal trocou), mas o nome bate.
            card("outro nome aqui", "https://yt3.ggpht.com/ZZZ=s48-c-k", "Nível Prata"),
            // Não está entre as inscrições: não tem como saber qual canal é.
            card("Canal Desconhecido", "https://yt3.ggpht.com/XXX=s48-c-k", "Nível Bronze"),
            section("Assinaturas inativas"),
            card("Canal Antigo", "https://yt3.ggpht.com/CCC=s48-c-k", "Nível Ouro", "Expirou em ", "15 de ago. de 2026"),
        )
        val members = parseActiveMemberships(root, known)
        assertEquals(listOf("UC1", "UC2"), members.map { it.id })
    }

    @Test
    fun pagina_sem_membros_ou_em_outro_idioma_nao_quebra() {
        val known = listOf(yt("UC1", "Canal", "https://yt3.ggpht.com/AAA=s68"))
        assertTrue(parseActiveMemberships(page(section("Assinaturas"), card("YouTube Premium", "https://www.gstatic.com/x.png")), known).isEmpty())
        assertTrue(parseActiveMemberships(page(), known).isEmpty())
        val english = page(section("Memberships"), card("Canal", "https://yt3.ggpht.com/AAA=s48"), section("Inactive memberships"), card("Canal Velho", "https://yt3.ggpht.com/ZZZ=s48"))
        assertEquals(listOf("UC1"), parseActiveMemberships(english, known).map { it.id })
    }

    // ---------------------------------------------------------------- dados

    @Test
    fun apoiar_marca_remove_e_inscreve_quem_faltava() {
        val d = data()
        val gaules = Channel(Platform.Twitch, "gaules", "Gaules")
        val filipe = Channel(Platform.Twitch, "filiperaaamos", "FilipeRaaamos")
        val kick = Channel(Platform.Kick, "yoda", "Yoda")
        val ytCanal = yt("UC1", "Canal")
        d.addSubscriptions(listOf(gaules, kick, ytCanal))

        // O sub da Twitch é de um canal que ainda não estava nas inscrições: entra marcado.
        d.setSupport(Platform.Twitch, listOf(filipe to SupportKind.Sub))
        assertEquals(SupportKind.Sub, d.subscriptions.value.single { it.id == "filiperaaamos" }.support)
        assertNull(d.subscriptions.value.single { it.id == "gaules" }.support)

        // Marcar a Twitch não mexe nas outras plataformas.
        d.setSupport(Platform.YouTube, listOf(ytCanal to SupportKind.Member))
        assertEquals(SupportKind.Member, d.subscriptions.value.single { it.id == "UC1" }.support)
        assertEquals(SupportKind.Sub, d.subscriptions.value.single { it.id == "filiperaaamos" }.support)
        assertNull(d.subscriptions.value.single { it.id == "yoda" }.support)

        // Deixou de pagar na Twitch: a marca sai, o canal continua inscrito.
        d.setSupport(Platform.Twitch, emptyList())
        assertNull(d.subscriptions.value.single { it.id == "filiperaaamos" }.support)
        assertEquals(4, d.subscriptions.value.size)
        assertEquals(SupportKind.Member, d.subscriptions.value.single { it.id == "UC1" }.support)
    }

    @Test
    fun a_marca_de_apoio_vai_e_volta_na_sincronizacao_e_aparelho_antigo_ignora() {
        val canal = Channel(Platform.Twitch, "gaules", "Gaules", support = SupportKind.Sub)
        val json = AppJson.encodeToString(Channel.serializer(), canal)
        assertTrue("\"support\":\"Sub\"" in json, json)
        assertEquals(SupportKind.Sub, AppJson.decodeFromString(Channel.serializer(), json).support)
        // Dado antigo (sem o campo) continua valendo.
        assertNull(AppJson.decodeFromString(Channel.serializer(), """{"platform":"Twitch","id":"x","name":"X"}""").support)
    }

    // ---------------------------------------------------------------- imagens

    @Test
    fun lista_nova_manda_baixar_miniatura_e_avatar_sem_repetir() {
        val canal = Channel(Platform.Kick, "yoda", "Yoda", "https://files.kick.com/a.webp")
        val itens = listOf(
            Media(Platform.Kick, "yoda", "Live", url = "u", channel = canal, thumbnailUrl = "https://images.kick.com/t1.webp"),
            Media(Platform.Kick, "yoda2", "Live 2", url = "u", channel = canal, thumbnailUrl = "https://images.kick.com/t2.webp"),
            Media(Platform.YouTube, "x", "Sem imagem", url = "u"),
            canal,
        )
        val urls = ImagePrefetch.urlsOf(itens)
        assertEquals(listOf("https://images.kick.com/t1.webp", "https://files.kick.com/a.webp", "https://images.kick.com/t2.webp"), urls)
        assertTrue(ImagePrefetch.urlsOf(listOf(Media(Platform.YouTube, "x", "t", thumbnailUrl = "file:///C:/x.png", url = "u"), "texto", null)).isEmpty())

        var recebido: List<String>? = null
        ImagePrefetch.enqueue = { recebido = it }
        ImagePrefetch.request(itens)
        assertNotNull(recebido)
        assertEquals(3, recebido!!.size)
        ImagePrefetch.enqueue = {}
    }
}
