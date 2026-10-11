package app.apex.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.player.volumeFromSlider
import app.apex.player.maxVolume
import app.apex.player.sliderFromVolume
import app.apex.player.NORMAL_VOLUME
import app.apex.player.PLAYBACK_SPEEDS
import app.apex.nav.Route
import app.apex.player.LoadState
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.IconBtn
import app.apex.ui.components.LiveTag
import app.apex.ui.components.RemoteImage
import app.apex.util.currentTimeMillis
import app.apex.util.formatClock
import kotlinx.coroutines.delay

private enum class PlayerMenu { Main, Quality, Speed, Subtitles }


/** Vídeo + controles. Usado na página do vídeo e em tela cheia. */
@Composable
fun PlayerView(modifier: Modifier = Modifier, fullscreen: Boolean = false) {
    val app = LocalApp.current
    val session = app.session
    val player = app.player
    val ps by player.state.collectAsState()
    val load by session.load.collectAsState()
    val current by session.current.collectAsState()
    val quality by session.quality.collectAsState()
    val subtitle by session.subtitle.collectAsState()
    val upNext by session.upNext.collectAsState()
    val resolved = (load as? LoadState.Ready)?.resolved
    val isLive = resolved?.isLive == true

    var visible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableLongStateOf(currentTimeMillis()) }
    var menu by remember { mutableStateOf<PlayerMenu?>(null) }

    LaunchedEffect(ps.playing, menu) {
        while (true) {
            delay(400)
            visible = !(ps.playing && menu == null && currentTimeMillis() - lastActivity > 3000)
        }
    }

    Box(
        modifier.background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastActivity = currentTimeMillis()
                        if (!visible) visible = true
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (menu != null) menu = null else player.togglePause() },
                    onDoubleTap = { app.setPlayerFullscreen(!fullscreen) },
                )
            },
    ) {
        player.Video(Modifier.fillMaxSize())

        when (val l = load) {
            is LoadState.Loading -> {
                RemoteImage(current?.thumbnailUrl, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = ApexColors.Accent)
                    Text("Preparando o vídeo…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is LoadState.Error -> {
                Box(Modifier.fillMaxSize().background(Color(0xE6000000)))
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).widthMax(520),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(l.message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    ActionButton("Tentar de novo", { current?.let { session.open(it) } }, primary = true)
                }
            }
            else -> {
                val waiting = ps.loading || (ps.hasMedia && !ps.playing && !ps.ended && ps.positionMs == 0L)
                if (waiting && ps.error == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = ApexColors.Accent)
                }
                ps.error?.let {
                    Text(it, Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (fullscreen) {
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBtn(Icons.Rounded.ArrowBack, "Sair da tela cheia", { app.setPlayerFullscreen(false) }, tint = Color.White)
                        Text(current?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
                Controls(fullscreen, isLive, menu) { menu = it }
                if (menu != null) {
                    SettingsPanel(menu!!, onMenu = { menu = it })
                }
            }
        }
    }
}

private fun Modifier.widthMax(dp: Int) = this.then(Modifier.width(dp.dp))

@Composable
private fun BoxScope.Controls(fullscreen: Boolean, isLive: Boolean, menu: PlayerMenu?, setMenu: (PlayerMenu?) -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val player = app.player
    val ps by player.state.collectAsState()
    val load by session.load.collectAsState()
    val subtitle by session.subtitle.collectAsState()
    val upNext by session.upNext.collectAsState()
    val resolved = (load as? LoadState.Ready)?.resolved

    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 14.dp).padding(top = 24.dp, bottom = 6.dp),
    ) {
        if (!isLive) {
            SeekBar(ps.positionMs, ps.durationMs, resolved?.chapters.orEmpty(), { player.seekTo(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (session.hasPrevious) PlayerBtn(Icons.Rounded.SkipPrevious, "Anterior") { session.previous() }
            PlayerBtn(if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ps.playing) "Pausar" else "Reproduzir") { player.togglePause() }
            if (upNext.isNotEmpty()) PlayerBtn(Icons.Rounded.SkipNext, "Próximo") { session.next() }

            VolumeControl()

            if (isLive) {
                LiveEdgeButton(ps.behindLiveMs, ps.playing, Modifier.padding(start = 8.dp)) { player.jumpToLive() }
            } else {
                Text(
                    "${formatClock(ps.positionMs)} / ${formatClock(ps.durationMs)}",
                    Modifier.padding(start = 10.dp), color = Color.White, style = MaterialTheme.typography.labelLarge,
                )
            }
            Box(Modifier.weight(1f))

            if (resolved?.subtitles?.isNotEmpty() == true) {
                PlayerBtn(
                    if (subtitle != null) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff, "Legendas",
                    tint = if (subtitle != null) ApexColors.Accent else Color.White,
                ) {
                    if (subtitle != null) session.setSubtitleTrack(null)
                    else session.setSubtitleTrack(
                        resolved.subtitles.firstOrNull { it.lang.startsWith(app.data.settings.value.subtitleLang) && !it.auto }
                            ?: resolved.subtitles.first(),
                    )
                }
            }
            PlayerBtn(Icons.Rounded.Settings, "Configurações", tint = if (menu != null) ApexColors.Accent else Color.White) {
                setMenu(if (menu != null) null else PlayerMenu.Main)
            }
            if (!fullscreen) {
                PlayerBtn(Icons.Rounded.Crop169, "Modo cinema", tint = if (app.ui.theater) ApexColors.Accent else Color.White) {
                    app.ui.theater = !app.ui.theater
                }
            }
            PlayerBtn(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fullscreen) "Sair da tela cheia" else "Tela cheia") {
                app.setPlayerFullscreen(!fullscreen)
            }
        }
    }
}

/** Mais que isso para trás (ou pausado) e o botão deixa de dizer "ao vivo" e passa a oferecer a volta. */
internal const val LIVE_EDGE_SLACK_MS = 6_000L

/**
 * O botão "AO VIVO" do player, como o da Twitch e do YouTube: vermelho quando o vídeo está em dia; cinza, com "Voltar ao vivo" e quantos segundos
 * ficou para trás, quando passou tempo (pausa, travadas). Apertar abre a live de novo no ponto mais novo: tira o atraso que se acumulou.
 */
@Composable
internal fun LiveEdgeButton(playerBehindMs: Long, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Pausado o player não manda mais a conta: o tempo parado soma aqui, de segundo em segundo (ao voltar a tocar o player já conta a pausa).
    var pausedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        pausedMs = 0
        if (!playing) while (true) { kotlinx.coroutines.delay(1_000); pausedMs += 1_000 }
    }
    val behindMs = playerBehindMs + pausedMs
    val atEdge = playing && behindMs < LIVE_EDGE_SLACK_MS
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val background = when {
        atEdge -> if (hovered) ApexColors.Live.copy(alpha = 0.85f) else ApexColors.Live
        else -> if (hovered) Color(0x66FFFFFF) else Color(0x40FFFFFF)
    }
    val label = when {
        atEdge -> "AO VIVO"
        behindMs >= 1_000 -> "Voltar ao vivo • ${behindMs / 1_000} s atrás"
        else -> "Voltar ao vivo"
    }
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).hoverable(source).background(background)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (atEdge) Color.White else ApexColors.Live, androidx.compose.foundation.shape.CircleShape))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun VolumeControl() {
    val app = LocalApp.current
    val ps by app.player.state.collectAsState()
    val settings by app.data.settings.collectAsState()
    val max = maxVolume(settings.volumeBoost)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(Modifier.hoverable(source), verticalAlignment = Alignment.CenterVertically) {
        val icon = when {
            ps.muted || ps.volume == 0 -> Icons.Rounded.VolumeOff
            ps.volume < 50 -> Icons.Rounded.VolumeDown
            else -> Icons.Rounded.VolumeUp
        }
        PlayerBtn(icon, "Volume") { app.player.setMuted(!ps.muted) }
        AnimatedVisibility(hovered) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Com o reforço (Ajustes) o controle vai até 200% e a marquinha no meio é o 100% (som normal), onde o arrasto "gruda";
                // sem ele vai até 100%, e a mudança é sempre imediata.
                MiniSlider(
                    if (ps.muted) 0f else sliderFromVolume(ps.volume, max),
                    { v -> app.setVolume(volumeFromSlider(v, max)) },
                    Modifier.width(110.dp).padding(horizontal = 6.dp),
                    markAt = if (max > NORMAL_VOLUME) sliderFromVolume(NORMAL_VOLUME, max) else null,
                )
                val shown = if (ps.muted) 0 else ps.volume
                Text(
                    "$shown%", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium,
                    // Acima de 100% o som é amplificado e pode distorcer: o número avisa.
                    color = if (shown > NORMAL_VOLUME) ApexColors.Support else Color.White,
                )
            }
        }
    }
}

@Composable
private fun PlayerBtn(icon: ImageVector, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconBtn(icon, description, onClick, size = 40.dp, iconSize = 24.dp, tint = tint, hoverColor = Color(0x33FFFFFF))
}

@Composable
private fun BoxScope.SettingsPanel(menu: PlayerMenu, onMenu: (PlayerMenu?) -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val ps by app.player.state.collectAsState()
    val load by session.load.collectAsState()
    val quality by session.quality.collectAsState()
    val subtitle by session.subtitle.collectAsState()
    val resolved = (load as? LoadState.Ready)?.resolved ?: return

    Column(
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 74.dp).width(270.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xF2181820))
            .pointerInput(Unit) { detectTapGestures { } }
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        when (menu) {
            PlayerMenu.Main -> {
                MenuRow("Qualidade", quality?.label ?: "—", chevron = true) { onMenu(PlayerMenu.Quality) }
                MenuRow("Velocidade", if (ps.rate == 1f) "Normal" else "${ps.rate}x", chevron = true) { onMenu(PlayerMenu.Speed) }
                if (resolved.subtitles.isNotEmpty()) MenuRow("Legendas", subtitle?.name ?: "Desativadas", chevron = true) { onMenu(PlayerMenu.Subtitles) }
            }
            PlayerMenu.Quality -> {
                MenuHeader("Qualidade") { onMenu(PlayerMenu.Main) }
                resolved.qualities.forEach { q ->
                    MenuRow(q.label, "", checked = q == quality) { session.setQuality(q); onMenu(null) }
                }
            }
            PlayerMenu.Speed -> {
                MenuHeader("Velocidade") { onMenu(PlayerMenu.Main) }
                PLAYBACK_SPEEDS.forEach { r ->
                    MenuRow(if (r == 1f) "Normal" else "${r}x", "", checked = r == ps.rate) { app.player.setRate(r); onMenu(null) }
                }
            }
            PlayerMenu.Subtitles -> {
                MenuHeader("Legendas") { onMenu(PlayerMenu.Main) }
                MenuRow("Desativadas", "", checked = subtitle == null) { session.setSubtitleTrack(null); onMenu(null) }
                resolved.subtitles.forEach { t ->
                    MenuRow(t.name, "", checked = t == subtitle) { session.setSubtitleTrack(t); onMenu(null) }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun MenuRow(label: String, value: String, chevron: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(source).background(if (hovered) Color(0x22FFFFFF) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp)) { if (checked) Icon(Icons.Rounded.Check, null, tint = ApexColors.Accent, modifier = Modifier.size(20.dp)) }
        Text(label, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (value.isNotEmpty()) Text(value, color = Color(0xFFB0B0C0), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFB0B0C0), modifier = Modifier.size(18.dp))
    }
}
