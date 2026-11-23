package de.cutlab.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Alle Untertitel in zeitlicher Reihenfolge auf einen Blick, direkt bearbeitbar. */
@Composable
fun SubtitleListScreen(vm: EditorViewModel) {
    BackHandler { vm.showSubList = false }
    val subs = vm.project.subs.sortedBy { it.startMs }
    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.showSubList = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
            Text("Untertitel bearbeiten (${subs.size})", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        }
        if (subs.isEmpty()) {
            Text("Keine Untertitel mehr vorhanden.", color = Color.Gray, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(subs, key = { it.id }) { s ->
                var text by remember(s.id) { mutableStateOf(s.text) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(s.startMs), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; vm.setSubTextLive(s.id, it) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.deleteSubById(s.id) }) { Icon(Icons.Default.Delete, "Löschen", tint = Color(0xFFFF8A80)) }
                }
            }
        }
    }
}
