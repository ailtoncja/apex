package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.ui.components.OnNearEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O "carregar mais" ao chegar perto do fim da lista tem de ir para a lista que está na tela agora (outra ordem, outra aba), não para a primeira. */
class OnNearEndTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun carregar_mais_vai_para_a_lista_atual_depois_de_trocar_de_lista() = runComposeUiTest {
        val asked = mutableListOf<String>()
        var current by mutableStateOf("recentes")
        val state = androidx.compose.foundation.lazy.grid.LazyGridState()
        setContent {
            Box(Modifier.requiredSize(400.dp, 300.dp)) {
                LazyVerticalGrid(GridCells.Fixed(1), state = rememberLazyGridState().let { state }) {
                    items((1..40).toList()) { Text("item $it", Modifier.height(60.dp)) }
                }
                // a tela de um canal troca a lista (a ordem escolhida) sem trocar a rolagem
                OnNearEnd(state) { asked += current }
            }
        }
        waitForIdle()
        current = "mais vistos"
        waitForIdle()
        runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(39) } }
        waitUntil(timeoutMillis = 5_000) { Snapshot.sendApplyNotifications(); asked.isNotEmpty() }
        assertEquals("mais vistos", asked.last(), "o pedido tem de ir para a lista nova; foi para: $asked")
        assertTrue("recentes" !in asked)
    }
}
