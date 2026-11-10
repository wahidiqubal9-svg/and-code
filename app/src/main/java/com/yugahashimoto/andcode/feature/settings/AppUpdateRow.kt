package com.yugahashimoto.andcode.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.launch

/**
 * "Check for app updates" row: fetches the newest release, then downloads the APK and hands it to
 * the system installer. Nothing installs silently; Android's own confirmation still appears.
 */
@Composable
fun AppUpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppUpdateRepository() }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AppUpdateInfo?>(null) }

    val checkingLabel = stringResource(R.string.app_update_checking)
    val upToDate = stringResource(R.string.app_update_up_to_date)
    val checkFailed = stringResource(R.string.app_update_check_failed)
    val downloadFailed = stringResource(R.string.app_update_download_failed)
    val installBlocked = stringResource(R.string.app_update_install_blocked)
    val installFailed = stringResource(R.string.app_update_install_failed)

    val valueText =
        when {
            checking -> checkingLabel
            downloading -> stringResource(R.string.app_update_downloading, progress)
            else -> status
        }

    SettingsRow(
        icon = Icons.Default.SystemUpdate,
        title = stringResource(R.string.app_update_row),
        value = valueText,
        onClick = {
            if (checking || downloading) return@SettingsRow
            checking = true
            status = null
            scope.launch {
                repository.checkForUpdate().fold(
                    onSuccess = { update ->
                        checking = false
                        if (update == null) {
                            status = upToDate
                        } else {
                            pending = update
                        }
                    },
                    onFailure = {
                        checking = false
                        status = checkFailed
                    },
                )
            }
        },
    )

    val update = pending
    if (update != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.app_update_available_title)) },
            text = { Text(stringResource(R.string.app_update_available_body, update.versionName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        downloading = true
                        progress = 0
                        status = null
                        scope.launch {
                            repository
                                .download(context, update) { value -> scope.launch { progress = value } }
                                .fold(
                                    onSuccess = { file ->
                                        downloading = false
                                        val started = runCatching { repository.install(context, file) }
                                        status =
                                            when {
                                                started.getOrDefault(false) -> null
                                                started.isFailure -> installFailed
                                                else -> installBlocked
                                            }
                                    },
                                    onFailure = {
                                        downloading = false
                                        status = downloadFailed
                                    },
                                )
                        }
                    },
                ) {
                    Text(stringResource(R.string.app_update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
