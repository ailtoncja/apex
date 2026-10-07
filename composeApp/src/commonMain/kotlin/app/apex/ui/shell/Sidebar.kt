package app.apex.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Platform
import app.apex.model.Channel
import androidx.compose.runtime.mutableStateMapOf
import app.apex.nav.LibraryTab
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.Avatar
import app.apex.ui.components.Divider

@Composable
fun Sidebar(expanded: Boolean) {
    val app = LocalApp.current
    val route = app.nav.current
    val width by animateDpAsState(if (expanded) 244.dp else 76.dp)
    val subs by app.data.subscriptions.collectAsState()
    val liveKeys by app.screens.subs.liveChannels.collectAsState()

    Column(
        Modifier.width(width).fillMaxHeight().background(ApexColors.Background).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NavItem(Icons.Rounded.Home, "Início", route is Route.Home, expanded) { app.nav.goRoot(Route.Home) }
        NavItem(Icons.Rounded.Sensors, "Ao vivo", route is Route.Live || route is Route.Category, expanded) {
            app.nav.goRoot(Route.Live(LiveFilter.All))
        }
        NavItem(Icons.Rounded.Subscriptions, "Inscrições", route is Route.Subscriptions, expanded) { app.nav.goRoot(Route.Subscriptions) }

        if (expanded) {
            Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            NavItem(Icons.Rounded.History, "Histórico", route is Route.Library && route.tab == LibraryTab.History, true) {
                app.nav.goRoot(Route.Library(LibraryTab.History))
            }
            NavItem(Icons.Rounded.WatchLater, "Assistir depois", route is Route.Library && route.tab == LibraryTab.WatchLater, true) {
                app.nav.goRoot(Route.Library(LibraryTab.WatchLater))
            }
            NavItem(Icons.Rounded.ThumbUp, "Curtidos", route is Route.Library && route.tab == LibraryTab.Liked, true) {
                app.nav.goRoot(Route.Library(LibraryTab.Liked))
            }
            NavItem(Icons.Rounded.ContentCut, "Clipes", route is Route.Library && route.tab == LibraryTab.Clips, true) {
                app.nav.goRoot(Route.Library(LibraryTab.Clips))
            }
            NavItem(Icons.Rounded.PlaylistPlay, "Playlists", route is Route.Library && route.tab == LibraryTab.Playlists || route is Route.PlaylistPage, true) {
                app.nav.goRoot(Route.Library(LibraryTab.Playlists))
            }
            Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            // Os canais ficam separados por plataforma (quem está ao vivo e quem a pessoa apoia aparece primeiro em cada grupo).
            val collapsed = remember { mutableStateMapOf<Platform, Boolean>() }
            val groups = remember(subs, liveKeys) {
                listOf(Platform.YouTube, Platform.Twitch, Platform.Kick).map { p ->
                    p to subs.filter { it.platform == p }.sortedWith(
                        compareByDescending<Channel> { it.key in liveKeys }.thenByDescending { it.support != null }.thenBy { it.name.lowercase() },
                    )
                }.filter { it.second.isNotEmpty() }
            }
            Text("Seus canais", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
            LazyColumn(Modifier.weight(1f)) {
                groups.forEach { (platform, list) ->
                    item("h-${platform.name}") {
                        val liveCount = list.count { it.key in liveKeys }
                        val closed = collapsed[platform] == true
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { collapsed[platform] = !closed }.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(platform.color(), CircleShape))
                            Text(
                                platform.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                color = ApexColors.OnSurface, maxLines = 1,
                            )
                            if (liveCount > 0) Text("$liveCount ao vivo", style = MaterialTheme.typography.labelSmall, color = ApexColors.Live)
                            Text("${list.size}", style = MaterialTheme.typography.labelSmall, color = ApexColors.Muted)
                            Text(if (closed) "▸" else "▾", style = MaterialTheme.typography.labelSmall, color = ApexColors.Muted)
                        }
                    }
                    if (collapsed[platform] != true) {
                        items(list, key = { it.key }) { ch ->
                            val source = remember { MutableInteractionSource() }
                            val hovered by source.collectIsHoveredAsState()
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hoverable(source)
                                    .background(if (hovered) ApexColors.SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable(interactionSource = source, indication = null) { app.openChannel(ch) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Ao vivo: a foto e a bolinha entram direto na live; o nome abre o canal.
                                val isLive = ch.key in liveKeys
                                Avatar(ch.avatarUrl, ch.name, 26.dp, if (isLive) Modifier.clip(CircleShape).clickable { app.openLive(ch) } else Modifier)
                                Text(ch.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isLive) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).clickable { app.openLive(ch) }, contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(8.dp).background(ApexColors.Live, CircleShape))
                                    }
                                } else if (ch.support != null) Box(Modifier.size(6.dp).background(ApexColors.Support, CircleShape))
                            }
                        }
                    }
                }
            }
        } else {
            NavItem(Icons.Rounded.VideoLibrary, "Biblioteca", route is Route.Library || route is Route.PlaylistPage, false) {
                app.nav.goRoot(Route.Library(LibraryTab.History))
            }
            Spacer(Modifier.weight(1f))
        }
        NavItem(Icons.Rounded.Settings, "Ajustes", route is Route.Settings, expanded) { app.nav.goRoot(Route.Settings) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        selected -> ApexColors.SurfaceHighest
        hovered -> ApexColors.SurfaceHigh
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val tint = if (selected) ApexColors.OnSurface else ApexColors.OnSurface.copy(alpha = 0.85f)
    if (expanded) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hoverable(source).background(bg)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, label, tint = if (selected) ApexColors.Accent else tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
        }
    } else {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hoverable(source).background(bg)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, label, tint = if (selected) ApexColors.Accent else tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}
