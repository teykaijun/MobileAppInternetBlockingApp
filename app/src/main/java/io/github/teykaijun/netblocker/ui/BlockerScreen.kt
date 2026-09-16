package io.github.teykaijun.netblocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.teykaijun.netblocker.R
import io.github.teykaijun.netblocker.data.InstalledApp
import io.github.teykaijun.netblocker.ui.theme.NetBlockerTheme
import io.github.teykaijun.netblocker.vpn.FailureReason
import io.github.teykaijun.netblocker.vpn.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockerScreen(
    state: MainUiState,
    query: String,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onBlockingChange: (Boolean) -> Unit,
    onAppBlockedChange: (String, Boolean) -> Unit,
    onBlockedOnlyChange: (Boolean) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onUnblockAll: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    OverflowMenu(
                        onUnblockAll = onUnblockAll,
                        onOpenVpnSettings = onOpenVpnSettings,
                        onOpenSupport = onOpenSupport,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            Modifier
                .padding(contentPadding)
                .fillMaxSize(),
        ) {
            StatusCard(
                state = state,
                onBlockingChange = onBlockingChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search))
                        }
                    }
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.blockedOnly,
                    onClick = { onBlockedOnlyChange(!state.blockedOnly) },
                    label = { Text(stringResource(R.string.filter_blocked_only)) },
                )
                FilterChip(
                    selected = state.showSystemApps,
                    onClick = { onShowSystemAppsChange(!state.showSystemApps) },
                    label = { Text(stringResource(R.string.filter_show_system)) },
                )
            }

            HorizontalDivider()

            when {
                state.loadingApps -> CenteredMessage {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.loading_apps), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.rows.isEmpty() -> CenteredMessage { Text(stringResource(R.string.empty_no_matches)) }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.app.packageName }) { row ->
                        AppListItem(row = row, onBlockedChange = onAppBlockedChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState, onBlockingChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val running = state.tunnel is TunnelState.Running
    val title = when {
        !state.blockingEnabled -> stringResource(R.string.status_off_title)
        state.blockedCount == 0 -> stringResource(R.string.status_on_no_apps_title)
        running -> pluralStringResource(R.plurals.status_on_title, state.blockedCount, state.blockedCount)
        else -> stringResource(R.string.status_starting)
    }
    val hint = when {
        state.blockingEnabled && state.blockedCount == 0 -> stringResource(R.string.status_on_no_apps_hint)
        state.blockingEnabled -> stringResource(R.string.status_on_hint)
        state.blockedCount == 0 -> stringResource(R.string.status_off_no_apps_hint)
        else -> pluralStringResource(R.plurals.status_off_selected_hint, state.blockedCount, state.blockedCount)
    }
    val switchLabel = stringResource(R.string.blocking_switch)
    val failure = (state.tunnel as? TunnelState.Failed)?.takeIf { !state.blockingEnabled }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(if (running) R.drawable.ic_shield else R.drawable.ic_block),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = state.blockingEnabled,
                onCheckedChange = onBlockingChange,
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        }
        if (failure != null) {
            Text(
                text = when (failure.reason) {
                    FailureReason.PERMISSION_REVOKED -> stringResource(R.string.status_revoked)
                    FailureReason.START_FAILED -> stringResource(R.string.status_start_failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun AppListItem(row: AppRow, onBlockedChange: (String, Boolean) -> Unit) {
    val app = row.app
    val blockedLabel = if (row.blocked) stringResource(R.string.app_blocked_badge) else null
    val systemLabel = if (app.isSystem) stringResource(R.string.app_system_badge) else null
    val sharedLabel = if (app.sharedUidPackages > 0) {
        pluralStringResource(R.plurals.app_shared_uid, app.sharedUidPackages, app.sharedUidPackages)
    } else {
        null
    }
    val labels = listOfNotNull(blockedLabel, systemLabel, sharedLabel)

    ListItem(
        modifier = Modifier.clickable { onBlockedChange(app.packageName, !row.blocked) },
        leadingContent = { AppIcon(app.packageName) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (labels.isNotEmpty()) {
                    Text(
                        text = labels.joinToString(" - "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.blocked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        trailingContent = {
            Switch(checked = row.blocked, onCheckedChange = { onBlockedChange(app.packageName, it) })
        },
    )
}

@Composable
private fun OverflowMenu(onUnblockAll: () -> Unit, onOpenVpnSettings: () -> Unit, onOpenSupport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_unblock_all)) },
            onClick = {
                expanded = false
                onUnblockAll()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_always_on)) },
            onClick = {
                expanded = false
                onOpenVpnSettings()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_support)) },
            onClick = {
                expanded = false
                onOpenSupport()
            },
        )
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Preview(showBackground = true)
@Composable
private fun BlockerScreenPreview() {
    val rows = listOf(
        AppRow(InstalledApp("com.example.browser", "Browser", false, true, 0), blocked = true),
        AppRow(InstalledApp("com.example.game", "Puzzle Game", false, true, 0), blocked = false),
    )
    NetBlockerTheme {
        BlockerScreen(
            state = MainUiState(
                loadingApps = false,
                rows = rows,
                blockedCount = 1,
                blockingEnabled = true,
                tunnel = TunnelState.Running(1),
            ),
            query = "",
            snackbarHostState = remember { SnackbarHostState() },
            onQueryChange = {},
            onBlockingChange = {},
            onAppBlockedChange = { _, _ -> },
            onBlockedOnlyChange = {},
            onShowSystemAppsChange = {},
            onUnblockAll = {},
            onOpenVpnSettings = {},
            onOpenSupport = {},
        )
    }
}
