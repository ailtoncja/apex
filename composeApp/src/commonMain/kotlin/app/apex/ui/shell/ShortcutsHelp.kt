package app.apex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.apex.theme.ApexColors

/** Um atalho: as maneiras de fazê-lo (cada uma é uma "tecla" na tela) e o que ele faz. */
internal class Shortcut(val keys: List<String>, val what: String)

internal class ShortcutGroup(val title: String, val items: List<Shortcut>)

/** Todos os atalhos do app (a lista que aparece com "?" e em Ajustes). Quem mexer em `handleShortcut` mexe aqui também. */
internal val SHORTCUT_GROUPS = listOf(
    ShortcutGroup(
        "Em qualquer tela",
        listOf(
            Shortcut(listOf("/", "Ctrl+K"), "Pesquisar"),
            Shortcut(listOf("Alt+←", "Alt+→"), "Voltar e avançar (também os botões laterais do mouse)"),
            Shortcut(listOf("Ctrl+1", "Alt+Home"), "Início"),
            Shortcut(listOf("Ctrl+2"), "Ao vivo"),
            Shortcut(listOf("Ctrl+3"), "Inscrições"),
            Shortcut(listOf("Ctrl+4"), "Histórico"),
            Shortcut(listOf("Ctrl+5", "Ctrl+,"), "Ajustes"),
            Shortcut(listOf("Ctrl+6"), "Multi (várias lives ao mesmo tempo)"),
            Shortcut(listOf("F5", "Ctrl+R"), "Atualizar a tela"),
            Shortcut(listOf("Ctrl+B"), "Mostrar ou esconder o menu lateral"),
            Shortcut(listOf("F11"), "Tela cheia da janela"),
            Shortcut(listOf("Esc"), "Sair da tela cheia, do modo cinema, do campo de pesquisa e da pesquisa"),
            Shortcut(listOf("?", "F1"), "Mostrar esta lista"),
        ),
    ),
    ShortcutGroup(
        "Com um vídeo ou uma live tocando",
        listOf(
            Shortcut(listOf("Espaço", "K"), "Pausar e continuar"),
            Shortcut(listOf("←", "→"), "Voltar e avançar 5 s"),
            Shortcut(listOf("J", "L"), "Voltar e avançar 10 s"),
            Shortcut(listOf("0 a 9"), "Pular para 0% a 90% do vídeo"),
            Shortcut(listOf("Home", "End"), "Ir para o começo e para o fim do vídeo (na live, End volta ao ao vivo)"),
            Shortcut(listOf("↑", "↓"), "Volume (até 200%)"),
            Shortcut(listOf("M"), "Silenciar"),
            Shortcut(listOf("<", ">"), "Diminuir e aumentar a velocidade"),
            Shortcut(listOf("F"), "Tela cheia do player"),
            Shortcut(listOf("T"), "Modo cinema"),
            Shortcut(listOf("C"), "Ligar e desligar a legenda"),
            Shortcut(listOf("Shift+N", "Shift+P"), "Próximo e anterior da fila"),
            Shortcut(listOf("Enter"), "Escrever no chat da live"),
        ),
    ),
)

/**
 * As teclas de cada atalho, como botões de teclado, e ao lado o que fazem. Os grupos vão um embaixo do outro, ou lado a lado em
 * [sideBySide] (na janelinha, que é larga).
 */
@Composable
fun ShortcutsList(modifier: Modifier = Modifier, sideBySide: Boolean = false) {
    if (sideBySide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            SHORTCUT_GROUPS.forEach { group -> ShortcutGroupColumn(group, Modifier.weight(1f)) }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SHORTCUT_GROUPS.forEach { group -> ShortcutGroupColumn(group, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun ShortcutGroupColumn(group: ShortcutGroup, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(group.title, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = ApexColors.Muted)
        group.items.forEach { shortcut ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.width(184.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    shortcut.keys.forEachIndexed { i, key ->
                        if (i > 0) Text("ou", style = MaterialTheme.typography.bodySmall, color = ApexColors.Faint)
                        KeyCap(key)
                    }
                }
                Text(shortcut.what, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun KeyCap(label: String) {
    Text(
        label,
        Modifier.background(ApexColors.SurfaceHighest, RoundedCornerShape(6.dp)).border(1.dp, ApexColors.Outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, maxLines = 1,
    )
}

/** A lista de atalhos numa janelinha por cima da tela (tecla "?" ou F1). */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.92f),
        // Sem a largura padrão (560 dp) para caberem os dois grupos lado a lado.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Atalhos de teclado") },
        text = { ShortcutsList(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), sideBySide = true) },
        confirmButton = { TextButton(onDismiss) { Text("Fechar") } },
        containerColor = ApexColors.SurfaceHigh,
    )
}
