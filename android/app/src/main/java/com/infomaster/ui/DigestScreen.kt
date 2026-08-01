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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infomaster.data.BudgetState
import com.infomaster.data.Digest
import com.infomaster.data.DigestItem
import com.infomaster.data.categoryLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(viewModel: DigestViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editing by viewModel.editingBudget.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今朝のAIダイジェスト", fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = viewModel::openBudgetEditor) { Text("残高") }
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
                    DigestList(
                        digest = current.digest,
                        budget = current.budget,
                        onEditBudget = viewModel::openBudgetEditor,
                    )
                }
            }
        }
    }

    if (editing) {
        val (balance, warnBelow) = remember { viewModel.currentSettings() }
        BalanceDialog(
            initialBalance = balance,
            initialWarnBelow = warnBelow,
            current = (state as? DigestUiState.Ready)?.budget,
            onDismiss = viewModel::dismissBudgetEditor,
            onSave = viewModel::saveBalance,
        )
    }
}

/**
 * 残高の入力。
 *
 * Anthropic には残高照会の API が無いため、Console で見た額を人が入れる。
 * 入れた後はパイプラインの消費記録から自動で減っていくので、
 * 触るのは補充したときだけでよい。
 */
@Composable
private fun BalanceDialog(
    initialBalance: Double,
    initialWarnBelow: Int,
    current: BudgetState?,
    onDismiss: () -> Unit,
    onSave: (Double, Int) -> Unit,
) {
    // 補充後に入れ直す場面が主なので、既存値ではなく現在の残高を初期表示にする。
    var balance by remember {
        val suggested = current?.takeIf { it.configured }?.remainingUsd ?: initialBalance
        mutableStateOf(if (suggested > 0) trimZeros(suggested) else "")
    }
    var warnBelow by remember { mutableStateOf(initialWarnBelow.toString()) }

    val balanceValue = balance.toDoubleOrNull()
    val warnValue = warnBelow.toIntOrNull()
    val valid =
        balanceValue != null && balanceValue > 0 && warnValue != null && warnValue >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("クレジット残高", fontSize = 18.sp) },
        text = {
            Column {
                Text(
                    "Console で確認した残高を入力してください。" +
                        "以降は使うたびに自動で減っていくので、" +
                        "触るのは補充したときだけで大丈夫です。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("現在の残高（USD）") },
                    placeholder = { Text("9.92") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = warnBelow,
                    onValueChange = { warnBelow = it },
                    label = { Text("残り何回分で警告するか") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (current != null && current.averageRunUsd > 0) {
                    val perRun = String.format("%.2f", current.averageRunUsd)
                    val runs = balanceValue?.let { (it / current.averageRunUsd).toInt() }
                    Text(
                        "1回あたり約\$$perRun" +
                            if (runs != null) "  →  約${runs}回分" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(balanceValue ?: 0.0, warnValue ?: 10) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

private fun trimZeros(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format("%.2f", value)

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
private fun DigestList(
    digest: Digest,
    budget: BudgetState,
    onEditBudget: () -> Unit,
) {
    val high = digest.items.filter { it.isHigh }
    val medium = digest.items.filterNot { it.isHigh }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(digest, budget, onEditBudget) }
        budget.alertMessage()?.let { alert ->
            item { AlertBanner(alert, onEditBudget) }
        }
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
private fun Header(digest: Digest, budget: BudgetState, onEditBudget: () -> Unit) {
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
        // 残高は常時見えるようにしておく。警告が出てから気づくのでは遅い。
        Text(
            budget.summary() ?: "残高を設定する",
            fontSize = 12.sp,
            color = if (budget.low) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(onClick = onEditBudget),
        )
    }
}

/**
 * クレジット残高などの運用警告。
 * 見落とすと「ある朝いきなり届かなくなる」ので、3行サマリより上に出す。
 */
@Composable
private fun AlertBanner(message: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp),
        // タップで残高の入力に飛べるようにする（補充したら即座に直せる）
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Text(
                "!",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                message,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
