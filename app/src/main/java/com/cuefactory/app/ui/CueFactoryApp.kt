package com.cuefactory.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import com.cuefactory.core.cue.CharsetProbe
import com.cuefactory.core.model.ConcurrencyConfig
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuefactory.app.CueFactoryViewModel
import com.cuefactory.app.UiState
import com.cuefactory.core.model.AmbiguousMatch
import com.cuefactory.core.model.JobState
import com.cuefactory.core.model.MatchedAlbum
import com.cuefactory.core.model.SplitJob

private data class TemplatePreset(
    val label: String,
    val directory: String,
    val fileName: String,
)

private val TEMPLATE_PRESETS = listOf(
    TemplatePreset(
        label = "艺人/专辑/序号. 标题",
        directory = "{album_artist}/{album}",
        fileName = "{track:02d}. {title}",
    ),
    TemplatePreset(
        label = "专辑/序号 - 标题",
        directory = "{album}",
        fileName = "{track:02d} - {title}",
    ),
    TemplatePreset(
        label = "艺人 - 专辑/序号. 标题",
        directory = "{album_artist} - {album}",
        fileName = "{track:02d}. {title}",
    ),
    TemplatePreset(
        label = "扁平：序号. 艺人 - 标题",
        directory = "",
        fileName = "{track:02d}. {artist} - {title}",
    ),
    TemplatePreset(
        label = "年份·专辑/序号. 标题",
        directory = "[{year}] {album}",
        fileName = "{track:02d}. {title}",
    ),
)

private val TEMPLATE_TOKENS = listOf(
    "{title}" to "标题",
    "{track:02d}" to "序号(02)",
    "{track}" to "序号",
    "{artist}" to "曲目艺人",
    "{album_artist}" to "专辑艺人",
    "{album}" to "专辑",
    "{year}" to "年份",
    "{genre}" to "流派",
    "{disc}" to "碟号",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CueFactoryApp(
    viewModel: CueFactoryViewModel,
    onRequestAllFilesAccess: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cue 工厂", fontWeight = FontWeight.SemiBold)
                        Text(
                            "整轨切分 · 精准匹配 · 无损优先",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.activeTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("扫描") },
                )
                NavigationBarItem(
                    selected = state.activeTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = {
                        val active = state.jobs.count {
                            it.state == JobState.Running || it.state == JobState.Pending
                        }
                        Text(if (active > 0) "队列($active)" else "队列")
                    },
                )
                NavigationBarItem(
                    selected = state.activeTab == 2,
                    onClick = { viewModel.setTab(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.activeTab) {
                0 -> ScanMatchTab(state, viewModel, onRequestAllFilesAccess)
                1 -> QueueTab(state, viewModel)
                else -> SettingsTab(state, viewModel, onRequestAllFilesAccess)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanMatchTab(
    state: UiState,
    viewModel: CueFactoryViewModel,
    onRequestAllFilesAccess: () -> Unit,
) {
    // Compact header so the selection list gets most of the screen.
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        if (!state.hasAllFilesAccess) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "需要「所有文件访问」权限",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Android 11+ 默认无法扫描 Music / 下载 等目录中的 CUE/FLAC。" +
                            "请授权后返回本页再点扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onRequestAllFilesAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("授权存储（所有文件）")
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.scanPathInput,
            onValueChange = viewModel::updateScanPath,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("扫描目录") },
            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "/storage/emulated/0/CueFactory/samples" to "样片",
                "/storage/emulated/0/Music" to "Music",
                "/storage/emulated/0/Download" to "下载",
            ).forEach { (path, label) ->
                SuggestionChip(
                    onClick = { viewModel.updateScanPath(path) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::startScan,
                enabled = !state.scanning,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.scanning) "扫描中…" else "扫描")
            }
            OutlinedButton(
                onClick = viewModel::cancelScan,
                enabled = state.scanning,
                modifier = Modifier.height(48.dp),
            ) { Text("取消") }
            Button(
                onClick = viewModel::enqueueSelected,
                enabled = state.selectedMatchedPaths.isNotEmpty() && !state.scanning,
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("切分(${state.selectedMatchedPaths.size})")
            }
        }

        AnimatedVisibility(visible = state.scanning) {
            Column(Modifier.padding(top = 6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.scanProgress?.let { p ->
                    Text(
                        "${p.visitedFiles} 文件 · 音频 ${p.audioFound} · CUE ${p.cueFound}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // Charset bar for Russian / multi-encoding
        Text(
            "CUE 编码（乱码时切换）",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CharsetProbe.uiChoices.take(8).forEach { (value, label) ->
                FilterChip(
                    selected = state.settings.cueCharset == value,
                    onClick = { viewModel.setGlobalCharset(value) },
                    label = { Text(label) },
                )
            }
        }

        ResultHeader(
            matched = state.matched.size,
            ambiguous = state.ambiguous.size,
            unmatched = state.unmatched.size,
            onSelectAll = { viewModel.selectAllMatched(true) },
            onSelectNone = { viewModel.selectAllMatched(false) },
        )

        // Primary interaction surface — takes remaining height
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
        ) {
            if (state.matched.isEmpty() && state.ambiguous.isEmpty() && state.unmatched.isEmpty() && !state.scanning) {
                item {
                    EmptyHint("扫描后在此勾选专辑。列表区域已加大，便于多选操作。歧义项需手动确认。")
                }
            }
            items(state.matched, key = { it.audio.path }) { album ->
                MatchedCard(
                    album = album,
                    selected = album.audio.path in state.selectedMatchedPaths,
                    onToggle = { viewModel.toggleMatched(album.audio.path) },
                )
            }
            itemsIndexed(state.ambiguous) { index, amb ->
                AmbiguousCard(
                    ambiguity = amb,
                    onConfirm = { audioPath, cuePath ->
                        viewModel.confirmAmbiguous(index, audioPath, cuePath)
                    },
                )
            }
            items(state.unmatched, key = { it.audio.path }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("未匹配 · ${item.audio.fileName}", style = MaterialTheme.typography.titleSmall)
                        Text(item.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(
    matched: Int,
    ambiguous: Int,
    unmatched: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "匹配 $matched · 歧义 $ambiguous · 未匹配 $unmatched",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Row {
            TextButton(onClick = onSelectAll) { Text("全选") }
            TextButton(onClick = onSelectNone) { Text("清空") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchedCard(
    album: MatchedAlbum,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val ruleZh = when (album.rule.name) {
        "Basename" -> "同名匹配"
        "CueFileDirective" -> "CUE FILE 指向"
        "UniquePairInDirectory" -> "目录唯一配对"
        "EmbeddedCue" -> "内嵌 CUE"
        "UserConfirmed" -> "手动确认"
        else -> album.rule.name
    }
    val originZh = when (album.cue.origin.name) {
        "External" -> "外置"
        "EmbeddedVorbis" -> "内嵌(Vorbis)"
        "EmbeddedNative" -> "内嵌(原生)"
        else -> album.cue.origin.name
    }
    val charsetLabel = album.sheet.sourceCharset ?: album.cue.charsetName ?: "自动"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            // Larger hit target for multi-select
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    album.sheet.title ?: album.audio.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        album.sheet.performer,
                        "${album.sheet.trackCount} 轨",
                        ruleZh,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(originZh)
                    StatusChip(charsetLabel)
                    StatusChip(album.audio.fileName.substringAfterLast('.').uppercase())
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun AmbiguousCard(
    ambiguity: AmbiguousMatch,
    onConfirm: (audioPath: String, cuePath: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("需要确认（不会自动开切）", style = MaterialTheme.typography.titleSmall)
            }
            Text(ambiguity.message, style = MaterialTheme.typography.bodySmall)
            ambiguity.audioCandidates.forEach { audio ->
                ambiguity.cueCandidates.forEach { cue ->
                    FilledTonalButton(
                        onClick = { onConfirm(audio.path, cue.path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${audio.fileName}  ×  ${cue.displayName}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTab(state: UiState, viewModel: CueFactoryViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("任务队列", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "支持取消与失败重试 · 切分时使用前台服务",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (state.jobs.isEmpty()) {
            EmptyHint("暂无任务。请在「扫描」页勾选匹配结果后加入队列。")
            return
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.jobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    onCancel = { viewModel.cancelJob(job.id) },
                    onRetry = { viewModel.retryJob(job.id) },
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: SplitJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val stateZh = when (job.state) {
        JobState.Pending -> "等待中"
        JobState.Running -> "运行中"
        JobState.Cancelling -> "取消中"
        JobState.Succeeded -> "已完成"
        JobState.PartialSuccess -> "部分成功"
        JobState.Failed -> "失败"
        JobState.Cancelled -> "已取消"
    }
    val tint = when (job.state) {
        JobState.Succeeded -> MaterialTheme.colorScheme.primary
        JobState.Failed, JobState.Cancelled -> MaterialTheme.colorScheme.error
        JobState.PartialSuccess -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (job.state) {
                    JobState.Succeeded -> Icon(Icons.Default.CheckCircle, null, tint = tint)
                    JobState.Failed, JobState.Cancelled -> Icon(Icons.Default.Close, null, tint = tint)
                    else -> Icon(Icons.Default.PlayArrow, null, tint = tint)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.album.sheet.title ?: job.album.audio.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "$stateZh · ${job.completedTracks}/${job.totalTracks} · ${(job.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = tint,
                    )
                }
            }
            if (job.state == JobState.Running || job.state == JobState.Pending || job.state == JobState.Cancelling) {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            job.message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            job.result?.let { result ->
                Text(
                    "成功 ${result.successes.size} · 失败 ${result.failures.size}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.state == JobState.Running || job.state == JobState.Pending || job.state == JobState.Cancelling) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
                if (job.state == JobState.Failed ||
                    job.state == JobState.Cancelled ||
                    job.state == JobState.PartialSuccess
                ) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTab(
    state: UiState,
    viewModel: CueFactoryViewModel,
    onRequestAllFilesAccess: () -> Unit,
) {
    val s = state.settings
    var focusField by remember { mutableStateOf("file") } // dir | file

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("存储权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (state.hasAllFilesAccess) {
                "已授予「所有文件访问」——可扫描 Music / 下载等路径。"
            } else {
                "未授予「所有文件访问」。Android 11+ 扫描 CUE 整轨需要此权限。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.hasAllFilesAccess) {
            Button(
                onClick = onRequestAllFilesAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开系统设置授权")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("输出与命名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            value = s.outputRootPath,
            onValueChange = { v -> viewModel.updateSettings { it.copy(outputRootPath = v) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输出根目录") },
            leadingIcon = { Icon(Icons.Default.Home, null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(
                onClick = {
                    viewModel.updateSettings {
                        it.copy(outputRootPath = "/storage/emulated/0/CueFactory/split-out")
                    }
                },
                label = { Text("默认 split-out") },
            )
            SuggestionChip(
                onClick = {
                    viewModel.updateSettings {
                        it.copy(outputRootPath = "/storage/emulated/0/Music/Split")
                    }
                },
                label = { Text("Music/Split") },
            )
        }

        Text("快捷方案", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TEMPLATE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = s.outputTemplate.directoryTemplate == preset.directory &&
                        s.outputTemplate.fileNameTemplate == preset.fileName,
                    onClick = {
                        viewModel.updateSettings {
                            it.copy(
                                outputTemplate = it.outputTemplate.copy(
                                    directoryTemplate = preset.directory,
                                    fileNameTemplate = preset.fileName,
                                ),
                            )
                        }
                    },
                    label = { Text(preset.label) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("自定义模板", style = MaterialTheme.typography.titleSmall)
        Text(
            "点选下方变量可插入到当前编辑框（先点「目录」或「文件名」）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = focusField == "dir",
                onClick = { focusField = "dir" },
                label = { Text("编辑目录模板") },
            )
            FilterChip(
                selected = focusField == "file",
                onClick = { focusField = "file" },
                label = { Text("编辑文件名模板") },
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TEMPLATE_TOKENS.forEach { (token, zh) ->
                AssistChip(
                    onClick = {
                        if (focusField == "dir") {
                            viewModel.updateSettings {
                                it.copy(
                                    outputTemplate = it.outputTemplate.copy(
                                        directoryTemplate = it.outputTemplate.directoryTemplate + token,
                                    ),
                                )
                            }
                        } else {
                            viewModel.updateSettings {
                                val next = it.outputTemplate.fileNameTemplate + token
                                if (!next.contains("{title}")) return@updateSettings it
                                it.copy(
                                    outputTemplate = it.outputTemplate.copy(fileNameTemplate = next),
                                )
                            }
                        }
                    },
                    label = { Text("$zh $token") },
                )
            }
        }

        OutlinedTextField(
            value = s.outputTemplate.directoryTemplate,
            onValueChange = { v ->
                viewModel.updateSettings {
                    it.copy(outputTemplate = it.outputTemplate.copy(directoryTemplate = v))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("目录模板") },
            supportingText = { Text("示例：{album_artist}/{album}") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = s.outputTemplate.fileNameTemplate,
            onValueChange = { v ->
                if (!v.contains("{title}")) return@OutlinedTextField
                runCatching {
                    viewModel.updateSettings {
                        it.copy(outputTemplate = it.outputTemplate.copy(fileNameTemplate = v))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("文件名模板（必须含 {title}）") },
            supportingText = { Text("示例：{track:02d}. {title}") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        // Live preview
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("预览", style = MaterialTheme.typography.labelMedium)
                val previewDir = s.outputTemplate.directoryTemplate
                    .replace("{album_artist}", "上海アリス幻楽団")
                    .replace("{album}", "東方永夜抄")
                    .replace("{year}", "2004")
                    .replace("{artist}", "ZUN")
                    .replace("{genre}", "Soundtrack")
                    .replace("{disc}", "1")
                    .replace("{title}", "标题")
                    .replace("{track:02d}", "02")
                    .replace("{track}", "2")
                val previewFile = s.outputTemplate.fileNameTemplate
                    .replace("{album_artist}", "上海アリス幻楽団")
                    .replace("{album}", "東方永夜抄")
                    .replace("{year}", "2004")
                    .replace("{artist}", "ZUN")
                    .replace("{genre}", "Soundtrack")
                    .replace("{disc}", "1")
                    .replace("{title}", "幻視の夜")
                    .replace("{track:02d}", "02")
                    .replace("{track}", "2")
                Text(
                    buildString {
                        if (previewDir.isNotBlank()) append(previewDir).append('/')
                        append(previewFile).append(".flac")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("切分策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hybrid（本分支固定）", fontWeight = FontWeight.SemiBold)
                Text(
                    "对齐 → 整帧拷贝；不对齐 → 仅重编码首尾残帧、中间帧拷贝；失败再整轨无损重编码。" +
                        "样本数始终与 CUE 一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("并发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "用 +/- 调节，不会把数字清空。默认稳妥 1 任务 × 1 线程。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.concurrency.jobParallelism == 1 && s.concurrency.workerThreads == 1,
                onClick = {
                    viewModel.updateSettings {
                        it.copy(concurrency = ConcurrencyConfig.Balanced)
                    }
                },
                label = { Text("稳妥 1×1") },
            )
            FilterChip(
                selected = s.concurrency.jobParallelism == 1 && s.concurrency.workerThreads == 2,
                onClick = {
                    viewModel.updateSettings {
                        it.copy(concurrency = ConcurrencyConfig.Performance)
                    }
                },
                label = { Text("稍快 1×2") },
            )
        }

        IntStepper(
            label = "任务并行（同时几张专辑）",
            value = s.concurrency.jobParallelism,
            range = 1..4,
            onChange = { n ->
                viewModel.updateSettings {
                    it.copy(concurrency = it.concurrency.copy(jobParallelism = n))
                }
            },
        )
        IntStepper(
            label = "工作线程（单专辑内）",
            value = s.concurrency.workerThreads,
            range = 1..8,
            onChange = { n ->
                viewModel.updateSettings {
                    it.copy(concurrency = it.concurrency.copy(workerThreads = n))
                }
            },
        )

        var showEngine by remember { mutableStateOf(false) }
        TextButton(onClick = { showEngine = !showEngine }) {
            Text(if (showEngine) "隐藏高级引擎设置" else "高级：ffmpeg 引擎（一般无需改）")
        }
        AnimatedVisibility(visible = showEngine) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "引擎保存在应用私有目录，不会放在公共存储。" +
                        "仅在需要覆盖时使用公共路径导入一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "当前：${s.ffmpegPath.ifBlank { "（自动）" }}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("使用提示", style = MaterialTheme.typography.labelLarge)
                Text(
                    "• 仅 FLAC 切分（MVP）\n" +
                        "• 俄文乱码：扫描页切换 Windows-1251 / CP866\n" +
                        "• 日志：logcat -s CueFactory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun IntStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            IconButton(
                onClick = { onChange((value - 1).coerceIn(range.first, range.last)) },
                enabled = value > range.first,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "减少")
            }
            IconButton(
                onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
                enabled = value < range.last,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "增加")
            }
        }
    }
}
