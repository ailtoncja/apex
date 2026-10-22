package app.apex

import app.apex.player.isoMillisPrecise
import app.apex.player.parseLivePlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A leitura da lista de uma live (HLS), com os trechos adiantados da Twitch. */
class LivePlaylistTest {
    private val base = "https://playlist.example/v1/playlist/abc.m3u8"

    /** Uma lista como a da Twitch: 6 trechos completos de 2 s, arquivo de início (fMP4), hora do servidor e 2 trechos adiantados. */
    private fun twitch(sequence: Long = 100, prefetch: Boolean = true, ended: Boolean = false) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:6")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$sequence")
        appendLine("""#EXT-X-DATERANGE:ID="playlist-creation-1",CLASS="timestamp",START-DATE="2026-10-08T17:25:28.912Z",X-SERVER-TIME="1791480328.912"""")
        appendLine("""#EXT-X-MAP:URI="https://cdn.example/init"""")
        repeat(6) { i ->
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-10-08T17:25:%02d.591Z".format(14 + i * 2))
            appendLine("#EXTINF:2.000,live")
            appendLine("https://cdn.example/seg${sequence + i}")
        }
        if (prefetch) {
            appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.example/seg${sequence + 6}")
            appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.example/seg${sequence + 7}")
        }
        if (ended) appendLine("#EXT-X-ENDLIST")
    }

    @Test
    fun le_os_trechos_os_adiantados_e_a_hora_do_servidor() {
        val live = parseLivePlaylist(twitch(), base)!!
        assertEquals(8, live.segments.size)
        assertEquals(listOf(false, false, false, false, false, false, true, true), live.segments.map { it.prefetch })
        assertEquals(100, live.mediaSequence)
        assertEquals(1791480328912, live.serverTimeMs)
        assertEquals("https://cdn.example/init", live.segments[0].map)
        assertEquals("https://cdn.example/init", live.segments[7].map, "o adiantado usa o mesmo arquivo de início")
        // o primeiro adiantado começa onde o último completo termina (a lista não diz a hora dele)
        val last = live.segments[5]
        assertEquals(last.startMs!! + 2000, live.segments[6].startMs)
        assertEquals(live.segments[6].startMs!! + 2000, live.segments[7].startMs)
        assertEquals(2.0, live.segments[6].duration)
    }

    @Test
    fun live_encerrada_e_marcada() {
        assertEquals(true, parseLivePlaylist(twitch(prefetch = false, ended = true), base)!!.ended)
    }

    @Test
    fun a_lista_mestra_e_a_lista_vazia_nao_servem() {
        assertNull(parseLivePlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1x1\nhttps://x/v.m3u8\n", base))
        assertNull(parseLivePlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n", base))
        assertNull(parseLivePlaylist("isto nao e uma lista", base))
    }

    @Test
    fun enderecos_relativos_viram_absolutos() {
        val live = parseLivePlaylist("#EXTM3U\n#EXTINF:2.0,\nseg1.ts\n#EXTINF:2.0,\n/raiz/seg2.ts\n", "https://host.example/a/b/lista.m3u8?token=1")!!
        assertEquals(listOf("https://host.example/a/b/seg1.ts", "https://host.example/raiz/seg2.ts"), live.segments.map { it.uri })
    }

    @Test
    fun a_hora_tem_os_milissegundos() {
        assertEquals(1791480325591, isoMillisPrecise("2026-10-08T17:25:25.591Z"))
        assertEquals(1791480325000, isoMillisPrecise("2026-10-08T17:25:25Z"))
        assertNull(isoMillisPrecise("ontem"))
    }
}
