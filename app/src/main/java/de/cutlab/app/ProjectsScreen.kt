package de.cutlab.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectsScreen(vm: EditorViewModel) {
    BackHandler { vm.showProjects = false }
    var rename by remember { mutableStateOf<ProjectInfo?>(null) }
    var delete by remember { mutableStateOf<ProjectInfo?>(null) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.showProjects = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
            Text("Projekte", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Button(onClick = { vm.createProject() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Neu") }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.projectList, key = { it.id }) { info ->
                val current = info.id == vm.currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF20202A))
                        .clickable { vm.openProject(info.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val bmp = info.thumbUri?.let { vm.thumbs.get(it, info.thumbSec) }
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303040)), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.Movie, null, tint = Color.Gray)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(info.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                        Text(
                            (if (info.durMs > 0) fmtTime(info.durMs) + "  ·  " else "leer  ·  ") +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.modifiedMs)),
                            color = Color.Gray, fontSize = 12.sp
                        )
                        if (current) Text("Geöffnet", color = Color(0xFF69F0AE), fontSize = 12.sp)
                    }
                    IconButton(onClick = { rename = info }) { Icon(Icons.Default.Edit, "Umbenennen", tint = Color.White) }
                    IconButton(onClick = { delete = info }) { Icon(Icons.Default.Delete, "Löschen", tint = Color.White) }
                }
            }
        }
    }

    rename?.let { info ->
        var name by remember(info) { mutableStateOf(info.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Projekt umbenennen") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProject(info.id, name); rename = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Abbrechen") } }
        )
    }
    delete?.let { info ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Projekt löschen?") },
            text = { Text("\"${info.name}\" und die zugehörigen importierten Dateien werden gelöscht. Exportierte Videos bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(info.id); delete = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } }
        )
    }
}
