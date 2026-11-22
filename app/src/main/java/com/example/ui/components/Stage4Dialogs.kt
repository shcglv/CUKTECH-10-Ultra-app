package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.cuktech.updater.GitHubApkUpdater
import com.example.cuktech.updater.ReleaseUpdateInfo
import com.example.cuktech.updater.UpdateDownloadState
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clean = minuteOfDay.coerceIn(0, 1439)
    return String.format(Locale.US, "%02d:%02d", clean / 60, clean % 60)
}

@Composable
fun SaveChargingProfileDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.profile_save_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim()) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("profile_save_confirm_button")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun EncryptedBackupExportDialog(
    onLaunchSafSave: (passphrase: CharArray, includeSecrets: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(false) }

    val tooShort = pass.isNotEmpty() && pass.length < 6
    val mismatch = confirm.isNotEmpty() && pass != confirm
    val canProceed = pass.length >= 6 && pass == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password)
                            )
                        }
                    },
                    singleLine = true,
                    isError = tooShort,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_export_pass_input")
                )
                if (tooShort) {
                    Text(
                        text = stringResource(R.string.backup_passphrase_min_len),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    isError = mismatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_export_confirm_input")
                )
                if (mismatch) {
                    Text(
                        text = stringResource(R.string.backup_passphrase_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeSecrets = !includeSecrets },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeSecrets,
                        onCheckedChange = { includeSecrets = it }
                    )
                    Text(
                        text = stringResource(R.string.backup_include_secrets),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (includeSecrets) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.backup_include_secrets_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchSafSave(pass.toCharArray(), includeSecrets) },
                enabled = canProceed,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("backup_export_saf_button")
            ) {
                Text(stringResource(R.string.backup_choose_saf_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun EncryptedBackupRestoreDialog(
    onLaunchSafOpen: (passphrase: CharArray, replaceExisting: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var replaceExisting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password)
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_restore_pass_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { replaceExisting = false },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !replaceExisting, onClick = { replaceExisting = false })
                    Text(stringResource(R.string.backup_restore_mode_merge), style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { replaceExisting = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = replaceExisting, onClick = { replaceExisting = true })
                    Text(stringResource(R.string.backup_restore_mode_replace), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchSafOpen(pass.toCharArray(), replaceExisting) },
                enabled = pass.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("backup_restore_saf_button")
            ) {
                Text(stringResource(R.string.backup_choose_saf_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun GitHubUpdateDialog(
    releaseInfo: ReleaseUpdateInfo?,
    downloadState: UpdateDownloadState,
    isChecking: Boolean,
    onCheckNow: () -> Unit,
    onDownloadAndVerify: () -> Unit,
    onResetDownloadState: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentVer = releaseInfo?.currentVersion ?: BuildConfig.VERSION_NAME

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
        title = { Text(stringResource(R.string.updater_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isChecking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_check_updates_now), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (releaseInfo == null || !releaseInfo.isUpdateAvailable) {
                    Text(
                        text = stringResource(R.string.updater_up_to_date_msg, currentVer),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.updater_current_latest_fmt,
                            releaseInfo.currentVersion,
                            releaseInfo.latestVersion
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (!releaseInfo.apkName.isNullOrBlank()) {
                        val sizeMb = releaseInfo.apkSizeBytes / (1024.0 * 1024.0)
                        Text(
                            text = stringResource(R.string.updater_apk_info_fmt, releaseInfo.apkName, sizeMb),
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!releaseInfo.expectedSha256.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.updater_sha256_expected,
                                    "${releaseInfo.expectedSha256.take(16)}…${releaseInfo.expectedSha256.takeLast(12)}"
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.updater_sha256_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    if (releaseInfo.changelog.isNotBlank()) {
                        Text(stringResource(R.string.updater_changelog), style = MaterialTheme.typography.labelLarge)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                        ) {
                            Text(
                                text = releaseInfo.changelog.take(1500),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    }

                    when (downloadState) {
                        is UpdateDownloadState.Downloading -> {
                            val readMb = downloadState.bytesRead / (1024.0 * 1024.0)
                            val totalMb = (downloadState.totalBytes.coerceAtLeast(1L)) / (1024.0 * 1024.0)
                            Text(
                                text = stringResource(R.string.updater_downloading_fmt, readMb, totalMb),
                                style = MaterialTheme.typography.bodySmall.tabularNums()
                            )
                            val progress = if (downloadState.totalBytes > 0L) {
                                (downloadState.bytesRead.toFloat() / downloadState.totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        is UpdateDownloadState.Verifying -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.updater_verifying_sha256), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is UpdateDownloadState.ReadyToInstall -> {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.updater_ready_install),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        is UpdateDownloadState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = downloadState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        },
        confirmButton = {
            when {
                isChecking -> Unit
                releaseInfo?.isUpdateAvailable == true && downloadState is UpdateDownloadState.ReadyToInstall -> {
                    val canInstall = GitHubApkUpdater.canRequestPackageInstalls(context)
                    Button(
                        onClick = {
                            if (!canInstall) {
                                runCatching {
                                    context.startActivity(GitHubApkUpdater.buildInstallSettingsIntent(context))
                                }
                            } else {
                                runCatching {
                                    context.startActivity(
                                        GitHubApkUpdater.buildInstallApkIntent(context, downloadState.apkFile)
                                    )
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        err.message ?: context.getString(R.string.toast_install_launch_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("updater_install_apk_button")
                    ) {
                        Text(
                            if (canInstall) {
                                stringResource(R.string.updater_install_apk_btn)
                            } else {
                                stringResource(R.string.updater_allow_unknown_sources)
                            }
                        )
                    }
                }
                releaseInfo?.isUpdateAvailable == true &&
                    !releaseInfo.apkDownloadUrl.isNullOrBlank() &&
                    !releaseInfo.expectedSha256.isNullOrBlank() &&
                    downloadState !is UpdateDownloadState.Downloading &&
                    downloadState !is UpdateDownloadState.Verifying -> {
                    Button(
                        onClick = onDownloadAndVerify,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("updater_download_verify_button")
                    ) {
                        Text(stringResource(R.string.updater_download_verify_btn))
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = onCheckNow,
                        enabled = !isChecking,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_check_updates_now))
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val url = releaseInfo?.htmlUrl ?: GitHubApkUpdater.RELEASES_PAGE_URL
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.updater_open_github))
                }
                TextButton(
                    onClick = {
                        if (downloadState is UpdateDownloadState.Error) {
                            onResetDownloadState()
                        }
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.cd_close))
                }
            }
        }
    )
}
