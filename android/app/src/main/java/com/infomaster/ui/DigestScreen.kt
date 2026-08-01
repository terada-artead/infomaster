package com.infomaster.ui

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infomaster.data.Digest
import com.infomaster.data.DigestItem
import com.infomaster.data.categoryLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(viewModel: DigestViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今朝のAIダイジェスト", fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("更新") }
                },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                is DigestUiState.Loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center)
                )

                is DigestUiState.Error -> ErrorView(current.message, viewModel::refresh)

                is DigestUiState.Ready -> {
                    if (current.refreshing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    DigestList(current.digest)
                }
            }
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ダイジェストを取得できませんでした", fontWeight = FontWeight.Medium)
        Text(
            message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("再試行")
        }
    }
}

@Composable
private fun DigestList(digest: Digest) {
    val high = digest.items.filter { it.isHigh }
    val medium = digest.items.filterNot { it.isHigh }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(digest) }
        if (digest.highlights.isNotEmpty()) {
            item { Highlights(digest.highlights) }
        }
        if (high.isNotEmpty()) {
            item { SectionLabel("重要") }
            items(high, key = { it.id }) { ItemCard(it) }
        }
        if (medium.isNotEmpty()) {
            item { SectionLabel("その他") }
            items(medium, key = { it.id }) { ItemCard(it) }
        }
    }
}

@Composable
private fun Header(digest: Digest) {
    Column {
        Text(
            digest.date,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${digest.stats.collected}件を収集 → ${digest.items.size}件に絞り込み",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Highlights(highlights: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "今日の3行",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            highlights.forEach { line ->
                Text(
                    "・$line",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ItemCard(item: DigestItem) {
    val context = LocalContext.current
    val primaryUrl = item.sources.firstOrNull()?.url

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = primaryUrl != null) {
                primaryUrl?.let { openLink(context, it) }
            },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(item.category)
                if (item.isHigh) {
                    Text(
                        "重要度 高",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                item.titleJa,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                item.summaryJa,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (item.sources.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    item.sources.joinToString("、") { it.name },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(category: String) {
    Box(
        Modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            categoryLabel(category),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 原文は Custom Tabs で開く。アプリを離れずに戻ってこられる。 */
private fun openLink(context: Context, url: String) {
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    }
}
