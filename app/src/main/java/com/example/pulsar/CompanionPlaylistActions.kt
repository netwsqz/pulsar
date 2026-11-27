package com.example.pulsar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track
import com.example.pulsar.ui.components.MediaRowText
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.launch

@Composable
internal fun CompanionPlaylistListActions(
    canCreate: Boolean,
    onRefresh: () -> Unit,
    create: suspend (String) -> Result<Playlist>,
    onCreated: (Playlist) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        Button(onClick = { creating = true }, enabled = canCreate) { Text("新建播放列表") }
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!saving) creating = false },
            title = { Text("新建播放列表") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = { Text("名称") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && canCreate && name.isNotBlank(),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                create(name).onSuccess {
                                    creating = false
                                    onCreated(it)
                                }.onFailure { error = it.message }
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(if (saving) "正在创建…" else "新建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }, enabled = !saving) { Text("取消") } },
        )
    }
}

@Composable
internal fun CompanionPlaylistEditAction(
    playlistId: String,
    order: List<String>,
    catalog: List<Track>,
    enabled: Boolean,
    save: suspend (base: List<String>, order: List<String>) -> Result<Playlist>,
) {
    var base by remember(playlistId) { mutableStateOf<List<String>?>(null) }
    TextButton(onClick = { base = order }, enabled = enabled) { Text("编辑曲目") }
    base?.let { original ->
        PlaylistOrderDialog(original, catalog, save = { save(original, it) }, onClose = { base = null })
    }
}

@Composable
private fun PlaylistOrderDialog(
    original: List<String>,
    catalog: List<Track>,
    save: suspend (List<String>) -> Result<Playlist>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(original) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val byId = remember(catalog) { catalog.associateBy(Track::id) }

    fun change(order: List<String>) {
        draft = order
        error = null
    }

    fun move(from: Int, to: Int) = change(draft.toMutableList().apply { add(to, removeAt(from)) })

    AlertDialog(
        onDismissRequest = { if (!saving) { if (adding) adding = false else onClose() } },
        title = { Text(if (adding) "添加歌曲" else "编辑曲目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
                if (adding) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索曲库") }, singleLine = true,
                    )
                    val choices = remember(catalog, draft, query) {
                        val selected = draft.toHashSet()
                        val needle = query.trim()
                        catalog.filter { track ->
                            track.id !in selected && (track.title.contains(needle, ignoreCase = true) ||
                                track.artist.contains(needle, ignoreCase = true) || track.album.contains(needle, ignoreCase = true))
                        }
                    }
                    if (choices.isEmpty()) Text("没有可添加的歌曲")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = Dimens.PlaylistEditorListMaxHeight).weight(1f, fill = false)) {
                        items(choices, key = Track::id) { track ->
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = Dimens.TouchTargetSmall)
                                    .clickable(role = Role.Button, onClickLabel = "添加到播放列表") { change(draft + track.id) },
                                verticalArrangement = Arrangement.Center,
                            ) { MediaRowText(title = track.title, subtitle = "${track.artist} · ${track.album}") }
                        }
                    }
                } else {
                    TextButton(onClick = { adding = true }, enabled = !saving) { Text("添加歌曲") }
                    if (draft.isEmpty()) Text("播放列表还没有歌曲")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = Dimens.PlaylistEditorListMaxHeight).weight(1f, fill = false)) {
                        itemsIndexed(draft, key = { _, id -> id }) { index, id ->
                            val title = byId[id]?.title ?: "曲目暂不可用"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}. $title", Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { move(index, index - 1) }, enabled = !saving && index > 0) {
                                    Icon(Icons.Filled.ExpandLess, contentDescription = "上移 $title")
                                }
                                IconButton(onClick = { move(index, index + 1) }, enabled = !saving && index < draft.lastIndex) {
                                    Icon(Icons.Filled.ExpandMore, contentDescription = "下移 $title")
                                }
                                IconButton(onClick = { change(draft - id) }, enabled = !saving) {
                                    Icon(Icons.Filled.Close, contentDescription = "从播放列表移除 $title")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (adding) TextButton(onClick = { adding = false }) { Text("完成添加") }
            else TextButton(
                enabled = !saving && draft != original,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            save(draft).onSuccess { onClose() }.onFailure { error = it.message }
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "正在保存…" else "保存顺序") }
        },
        dismissButton = {
            if (!adding) TextButton(onClick = onClose, enabled = !saving) { Text("取消") }
        },
    )
}
