package com.example.ui.cuktech

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.bluetooth.BluetoothManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.ble.DiscoveredCuktechDevice
import com.example.cuktech.cloud.QrLoginSession
import com.example.cuktech.cloud.QrPollResult
import com.example.cuktech.cloud.TwoFactorChallenge
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.cloud.XiaomiLoginResult
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.security.XiaomiAccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XiaomiAccountLoginDialog(
    cloudClient: XiaomiCloudClient,
    credentialStore: SecureCredentialStore,
    onDismiss: () -> Unit,
    onDevicesSynced: (List<CuktechDeviceCredentials>) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Google / Web, 1 = QR Login, 2 = Password, 3 = Manual/JSON
    var selectedRegion by remember { mutableStateOf("cn") }

    // QR Login state
    var qrSession by remember { mutableStateOf<QrLoginSession?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var qrStatusText by remember { mutableStateOf(context.getString(R.string.xiaomi_qr_init)) }
    var isQrLoading by remember { mutableStateOf(false) }

    // Password Login state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginStatusText by remember { mutableStateOf("") }

    // 2FA Challenge state
    var active2faChallenge by remember { mutableStateOf<TwoFactorChallenge?>(null) }
    var otpCode by remember { mutableStateOf("") }
    var useEmail2fa by remember { mutableStateOf(false) }
    var showWebView2fa by remember { mutableStateOf(false) }

    // Manual/JSON credential entry
    var manualMac by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }
    var manualBleKey by remember { mutableStateOf("") }

    suspend fun handleAuthenticatedSession(session: XiaomiAccountSession) {
        credentialStore.saveSession(session)
        loginStatusText = context.getString(R.string.xiaomi_logged_in_fetching_fmt, session.userId)
        qrStatusText = loginStatusText
        val devRes = cloudClient.fetchDevicesAndBleKeys(session, searchAllRegionsIfEmpty = true)
        devRes.onSuccess { list ->
            if (list.isNotEmpty()) {
                credentialStore.saveDevices(list)
                onDevicesSynced(list)
            } else {
                onDevicesSynced(emptyList())
            }
        }.onFailure {
            onDevicesSynced(emptyList())
        }
        onDismiss()
    }

    // Automatically start QR login when tab 1 is opened
    LaunchedEffect(selectedTab, selectedRegion) {
        if (selectedTab == 1) {
            isQrLoading = true
            qrBitmap = null
            qrSession = null
            qrStatusText = context.getString(R.string.xiaomi_qr_creating_fmt, selectedRegion.uppercase())
            val res = cloudClient.startQrLogin(selectedRegion)
            isQrLoading = false
            res.onSuccess { session ->
                qrSession = session
                qrStatusText = context.getString(R.string.xiaomi_qr_scan_prompt)
                // Download QR image bitmap with bounded timeouts
                withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = (URL(session.qrImageUrl).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 10000
                            readTimeout = 10000
                        }
                        try {
                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.use { it.readBytes() }
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                qrBitmap = bmp?.asImageBitmap()
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                }
                // Start polling loop
                while (isActive && qrSession == session) {
                    when (val poll = cloudClient.pollQrLogin(session, selectedRegion)) {
                        is QrPollResult.Waiting -> {
                            // Continue waiting
                        }
                        is QrPollResult.Timeout -> {
                            qrStatusText = context.getString(R.string.xiaomi_qr_expired)
                            break
                        }
                        is QrPollResult.Requires2FA -> {
                            active2faChallenge = poll.challenge
                            qrStatusText = context.getString(R.string.xiaomi_qr_2fa_req)
                            break
                        }
                        is QrPollResult.Success -> {
                            handleAuthenticatedSession(poll.session)
                            break
                        }
                        is QrPollResult.Error -> {
                            qrStatusText = context.getString(R.string.xiaomi_qr_status_fmt, poll.message)
                            break
                        }
                    }
                }
            }.onFailure { e ->
                qrStatusText = context.getString(R.string.xiaomi_qr_failed_fmt, e.message ?: "")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.xiaomi_login_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                }

                // Region warning banner if existing logged-in account is not Mainland China
                val currentSession = credentialStore.session.value
                if (currentSession != null && !currentSession.region.equals("cn", ignoreCase = true)) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.xiaomi_region_warn_fmt, currentSession.region.uppercase()),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // If 2FA Challenge is active, show 2FA verification panel
                val challenge = active2faChallenge
                if (challenge != null) {
                    TwoFactorChallengeSection(
                        challenge = challenge,
                        otpCode = otpCode,
                        onOtpChange = { otpCode = it },
                        useEmail = useEmail2fa,
                        onToggleChannel = { useEmail2fa = it },
                        showWebView = showWebView2fa,
                        onToggleWebView = { showWebView2fa = it },
                        statusText = loginStatusText,
                        onSendCode = {
                            scope.launch {
                                loginStatusText = context.getString(R.string.xiaomi_2fa_requesting)
                                val r = cloudClient.send2faCode(challenge, useEmail2fa)
                                loginStatusText = if (r.isSuccess) {
                                    context.getString(R.string.xiaomi_2fa_code_sent)
                                } else {
                                    val errStr = r.exceptionOrNull()?.message ?: context.getString(R.string.xiaomi_2fa_send_failed)
                                    XiaomiCloudClient.translateXiaomiError(errStr)
                                }
                            }
                        },
                        onVerifyCode = {
                            scope.launch {
                                isLoggingIn = true
                                loginStatusText = context.getString(R.string.xiaomi_2fa_verifying)
                                when (val res = cloudClient.verify2faCode(challenge, otpCode, useEmail2fa)) {
                                    is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                    is XiaomiLoginResult.Error -> loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                    is XiaomiLoginResult.Requires2FA -> active2faChallenge = res.challenge
                                }
                                isLoggingIn = false
                            }
                        },
                        onWebViewFinishUrl = { finishUrl, webCookies ->
                            scope.launch {
                                isLoggingIn = true
                                loginStatusText = context.getString(R.string.xiaomi_2fa_web_extracting)
                                val mergedCookies = challenge.identitySessionCookies + webCookies
                                when (val res = cloudClient.complete2faFinishRedirect(
                                    finishUrl,
                                    mergedCookies,
                                    challenge.deviceId,
                                    selectedRegion
                                )) {
                                    is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                    is XiaomiLoginResult.Error -> loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                    is XiaomiLoginResult.Requires2FA -> active2faChallenge = res.challenge
                                }
                                isLoggingIn = false
                            }
                        },
                        onBack = {
                            active2faChallenge = null
                            showWebView2fa = false
                        }
                    )
                } else {
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = 0
                            },
                            text = { Text(stringResource(R.string.xiaomi_tab_web), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = 1
                            },
                            text = { Text(stringResource(R.string.xiaomi_tab_qr), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = 2
                            },
                            text = { Text(stringResource(R.string.xiaomi_tab_password), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = 3
                            },
                            text = { Text(stringResource(R.string.xiaomi_tab_manual), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }

                    when (selectedTab) {
                        0 -> {
                            // Web / Browser Login Tab
                            XiaomiWebLoginSection(
                                cloudClient = cloudClient,
                                selectedRegion = selectedRegion,
                                onSessionAuthenticated = { session ->
                                    scope.launch {
                                        handleAuthenticatedSession(session)
                                    }
                                },
                                onPasteFinishUrl = { finishUrl ->
                                    scope.launch {
                                        isLoggingIn = true
                                        loginStatusText = context.getString(R.string.xiaomi_web_verifying_url)
                                        when (val res = cloudClient.completeWebLogin(
                                            finishUrl = finishUrl,
                                            cookiesIn = emptyMap(),
                                            deviceId = cloudClient.generateDeviceId(),
                                            region = selectedRegion
                                        )) {
                                            is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                            is XiaomiLoginResult.Error -> loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                            is XiaomiLoginResult.Requires2FA -> active2faChallenge = res.challenge
                                        }
                                        isLoggingIn = false
                                    }
                                },
                                statusText = loginStatusText
                            )
                        }

                        1 -> {
                            // QR Code Login Tab
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(210.dp)
                                        .background(Color.White, RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        isQrLoading -> CircularProgressIndicator()
                                        qrBitmap != null -> Image(
                                            bitmap = qrBitmap!!,
                                            contentDescription = stringResource(R.string.xiaomi_tab_qr),
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        else -> Text(
                                            text = stringResource(R.string.xiaomi_qr_tap_refresh),
                                            color = Color.DarkGray,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Text(
                                    text = qrStatusText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            qrBitmap = null
                                            qrSession = null
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.xiaomi_qr_refresh_btn))
                                    }
                                }
                            }
                        }

                        2 -> {
                            // Username & Password Login Tab
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text(stringResource(R.string.xiaomi_pw_user_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Email,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                var passwordVisible by remember { mutableStateOf(false) }
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text(stringResource(R.string.xiaomi_pw_pass_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = stringResource(R.string.cd_toggle_password)
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = stringResource(R.string.xiaomi_pw_autofill_tip),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )

                                if (loginStatusText.isNotBlank()) {
                                    Text(
                                        text = loginStatusText,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (username.isBlank() || password.isBlank()) {
                                            loginStatusText = context.getString(R.string.xiaomi_pw_missing_fields)
                                            return@Button
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch {
                                            isLoggingIn = true
                                            loginStatusText = context.getString(R.string.xiaomi_pw_authenticating)
                                            when (val res = cloudClient.loginWithPassword(username, password, selectedRegion)) {
                                                is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                                is XiaomiLoginResult.Requires2FA -> {
                                                    active2faChallenge = res.challenge
                                                    loginStatusText = context.getString(R.string.xiaomi_pw_2fa_prompt)
                                                }
                                                is XiaomiLoginResult.Error -> {
                                                    loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                                }
                                            }
                                            isLoggingIn = false
                                        }
                                    },
                                    enabled = !isLoggingIn,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    if (isLoggingIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.xiaomi_pw_signin_btn))
                                }
                            }
                        }

                        3 -> {
                            // Manual / SAF JSON Import Tab
                            val importContext = LocalContext.current
                            val importJsonLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.OpenDocument()
                            ) { uri ->
                                if (uri != null) {
                                    scope.launch {
                                        val content = withContext(Dispatchers.IO) {
                                            runCatching {
                                                importContext.contentResolver.openInputStream(uri)?.use { stream ->
                                                    stream.bufferedReader().readText()
                                                }
                                            }.getOrNull()
                                        }
                                        val imported = content?.let { credentialStore.importFromJsonString(it) }
                                        if (imported != null) {
                                            onDevicesSynced(credentialStore.devices.value)
                                            onDismiss()
                                        } else {
                                            loginStatusText = importContext.getString(R.string.xiaomi_manual_import_invalid)
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.xiaomi_manual_import_btn))
                                }

                                OutlinedTextField(
                                    value = manualMac,
                                    onValueChange = { manualMac = it },
                                    label = { Text(stringResource(R.string.xiaomi_manual_mac_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = manualToken,
                                    onValueChange = { manualToken = it },
                                    label = { Text(stringResource(R.string.xiaomi_manual_token_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = manualBleKey,
                                    onValueChange = { manualBleKey = it },
                                    label = { Text(stringResource(R.string.xiaomi_manual_ble_key_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (loginStatusText.isNotBlank()) {
                                    Text(loginStatusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }

                                Button(
                                    onClick = {
                                        val cleanMac = manualMac.trim().uppercase()
                                        val cleanTok = manualToken.trim().lowercase()
                                        if (cleanMac.length < 11 || cleanTok.length != 24) {
                                            loginStatusText = context.getString(R.string.xiaomi_manual_invalid_input)
                                            return@Button
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val cred = CuktechDeviceCredentials(
                                            did = "manual_${cleanMac.replace(":", "")}",
                                            name = "CUKTECH 10 Ultra",
                                            model = "njcuk.fitting.ad1204",
                                            mac = cleanMac,
                                            tokenHex = cleanTok,
                                            bleKeyHex = manualBleKey.trim().lowercase(),
                                            region = selectedRegion
                                        )
                                        credentialStore.addOrUpdateDevice(cred)
                                        onDevicesSynced(credentialStore.devices.value)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.xiaomi_manual_save_btn))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TwoFactorChallengeSection(
    challenge: TwoFactorChallenge,
    otpCode: String,
    onOtpChange: (String) -> Unit,
    useEmail: Boolean,
    onToggleChannel: (Boolean) -> Unit,
    showWebView: Boolean,
    onToggleWebView: (Boolean) -> Unit,
    statusText: String,
    onSendCode: () -> Unit,
    onVerifyCode: () -> Unit,
    onWebViewFinishUrl: (String, Map<String, String>) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.xiaomi_2fa_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = buildString {
                append(stringResource(R.string.xiaomi_2fa_body))
                if (challenge.maskedPhone.isNotBlank()) {
                    append(" ")
                    append(stringResource(R.string.xiaomi_2fa_phone_fmt, challenge.maskedPhone))
                }
                if (challenge.maskedEmail.isNotBlank()) {
                    append(" ")
                    append(stringResource(R.string.xiaomi_2fa_email_fmt, challenge.maskedEmail))
                }
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useEmail,
                onClick = { onToggleChannel(false) },
                label = { Text(stringResource(R.string.xiaomi_2fa_channel_sms)) }
            )
            FilterChip(
                selected = useEmail,
                onClick = { onToggleChannel(true) },
                label = { Text(stringResource(R.string.xiaomi_2fa_channel_email)) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = otpCode,
                onValueChange = onOtpChange,
                label = { Text(stringResource(R.string.xiaomi_2fa_code_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onSendCode,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.xiaomi_2fa_send_btn), fontSize = 12.sp)
            }
        }

        if (statusText.isNotBlank()) {
            Text(text = statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.cd_back))
            }
            Button(
                onClick = onVerifyCode,
                enabled = otpCode.trim().length >= 4,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.xiaomi_2fa_verify_btn))
            }
        }

        TextButton(
            onClick = { onToggleWebView(!showWebView) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                if (showWebView) {
                    stringResource(R.string.xiaomi_2fa_web_collapse)
                } else {
                    stringResource(R.string.xiaomi_2fa_web_expand)
                }
            )
        }

        if (showWebView) {
            DisposableEffect(Unit) {
                onDispose {
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            for ((k, v) in challenge.identitySessionCookies) {
                                cm.setCookie("https://account.xiaomi.com", "$k=$v; Domain=.xiaomi.com; Path=/")
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val uri = request?.url ?: return false
                                    val scheme = uri.scheme?.lowercase().orEmpty()
                                    if (scheme != "https") {
                                        return true // Block non-HTTPS schemes (file://, content://, javascript:, http://)
                                    }
                                    val host = uri.host?.lowercase().orEmpty()
                                    val isAllowedHost = host.endsWith(".xiaomi.com") || host == "xiaomi.com" ||
                                        host.endsWith(".mi.com") || host == "mi.com" ||
                                        host.endsWith(".miui.com") ||
                                        host.endsWith(".google.com") || host.endsWith(".facebook.com")
                                    if (!isAllowedHost) {
                                        return true
                                    }
                                    val url = uri.toString()
                                    if (url.contains("sts.api.io.mi.com/sts") || url.contains("serviceToken=")) {
                                        val rawCookie = cm.getCookie("https://account.xiaomi.com").orEmpty() +
                                            "; " + cm.getCookie(url).orEmpty()
                                        val cookieMap = parseCookieString(rawCookie)
                                        onWebViewFinishUrl(url, cookieMap)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val u = url ?: return
                                    val rawCookie = cm.getCookie("https://sts.api.io.mi.com").orEmpty() +
                                        "; " + cm.getCookie("https://account.xiaomi.com").orEmpty()
                                    val cookieMap = parseCookieString(rawCookie)
                                    if (cookieMap.containsKey("serviceToken") || u.contains("sts.api.io.mi.com/sts")) {
                                        onWebViewFinishUrl(u, cookieMap)
                                    }
                                }
                            }
                            if (challenge.notificationUrl.startsWith("https://")) {
                                loadUrl(challenge.notificationUrl)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun XiaomiWebLoginSection(
    cloudClient: XiaomiCloudClient,
    selectedRegion: String,
    onSessionAuthenticated: (XiaomiAccountSession) -> Unit,
    onPasteFinishUrl: (String) -> Unit,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isStartingSession by remember { mutableStateOf(false) }
    var activeSession by remember { mutableStateOf<QrLoginSession?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var browserStatus by remember { mutableStateOf("") }
    var showManualPaste by remember { mutableStateOf(false) }
    var pastedUrl by remember { mutableStateOf("") }

    fun openBrowserLogin() {
        scope.launch {
            isStartingSession = true
            browserStatus = context.getString(R.string.xiaomi_web_preparing_fmt, selectedRegion.uppercase())
            pollJob?.cancel()

            val res = cloudClient.startQrLogin(selectedRegion)
            isStartingSession = false

            res.onSuccess { session ->
                activeSession = session
                browserStatus = context.getString(R.string.xiaomi_web_browser_opened)

                // Launch default web browser
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure {
                    browserStatus = context.getString(R.string.xiaomi_web_open_failed_fmt, it.message ?: "")
                }

                // Poll longPolling endpoint in background
                pollJob = scope.launch {
                    while (isActive && activeSession == session) {
                        when (val poll = cloudClient.pollQrLogin(session, selectedRegion)) {
                            is QrPollResult.Waiting -> {
                                // continue polling
                            }
                            is QrPollResult.Timeout -> {
                                browserStatus = context.getString(R.string.xiaomi_web_expired)
                                break
                            }
                            is QrPollResult.Requires2FA -> {
                                browserStatus = context.getString(R.string.xiaomi_web_2fa_hint)
                                break
                            }
                            is QrPollResult.Success -> {
                                browserStatus = context.getString(R.string.xiaomi_web_confirmed)
                                onSessionAuthenticated(poll.session)
                                break
                            }
                            is QrPollResult.Error -> {
                                browserStatus = context.getString(R.string.xiaomi_web_status_fmt, poll.message)
                                break
                            }
                        }
                    }
                }
            }.onFailure { err ->
                browserStatus = context.getString(R.string.xiaomi_web_start_failed_fmt, err.message ?: "")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pollJob?.cancel()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.xiaomi_web_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.xiaomi_web_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }

                Button(
                    onClick = { openBrowserLogin() },
                    enabled = !isStartingSession,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isStartingSession) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (activeSession == null) {
                            stringResource(R.string.xiaomi_web_open_btn)
                        } else {
                            stringResource(R.string.xiaomi_web_reopen_btn)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (activeSession != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = browserStatus.ifBlank { stringResource(R.string.xiaomi_web_waiting) },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (browserStatus.isNotBlank()) {
                    Text(
                        text = browserStatus,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://account.xiaomi.com")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.xiaomi_web_open_account), fontSize = 11.sp)
            }

            TextButton(
                onClick = { showManualPaste = !showManualPaste },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = if (showManualPaste) {
                        stringResource(R.string.xiaomi_web_paste_hide)
                    } else {
                        stringResource(R.string.xiaomi_web_paste_show)
                    },
                    fontSize = 11.sp
                )
            }
        }

        if (showManualPaste) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = pastedUrl,
                    onValueChange = { pastedUrl = it },
                    label = { Text(stringResource(R.string.xiaomi_web_paste_label)) },
                    placeholder = { Text("https://sts.api.io.mi.com/sts?d=...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (pastedUrl.isNotBlank()) {
                            onPasteFinishUrl(pastedUrl.trim())
                        }
                    },
                    enabled = pastedUrl.isNotBlank(),
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.xiaomi_web_submit_url))
                }
            }
        }
    }
}

private fun parseCookieString(raw: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (part in raw.split(";")) {
        val trimmed = part.trim()
        val idx = trimmed.indexOf('=')
        if (idx > 0) {
            map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
    }
    return map
}

/**
 * Prominent Material 3 Expressive "SEARCH & PAIR CUKTECH 10 ULTRA" discovery and pairing bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuktechDiscoveryPairingDialog(
    bleManager: CuktechBleManager,
    onDismiss: () -> Unit,
    onSelectAndConnect: (DiscoveredCuktechDevice) -> Unit
) {
    val discovered by bleManager.discoveredDevices.collectAsState()
    val authState by bleManager.authState.collectAsState()
    val isScanning by bleManager.isScanning.collectAsState()
    val statusMsg by bleManager.statusMessage.collectAsState()
    val savedCreds by bleManager.credentialStore.devices.collectAsState()
    val bleConnectionState by com.example.BleBridgeService.bleConnectionState.collectAsState()

    val isCurrentlyConnected = authState == CuktechAuthState.AUTHENTICATED ||
        authState == CuktechAuthState.CONNECTING_GATT ||
        authState == CuktechAuthState.DISCOVERING_SERVICES ||
        authState == CuktechAuthState.PHASE_A_INIT_A4 ||
        authState == CuktechAuthState.PHASE_B_HANDSHAKE ||
        bleConnectionState != com.example.BleBridgeService.Companion.BleConnectionState.DISCONNECTED

    var pendingSwitchDevice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    val requestConnectToDevice: (String, String) -> Unit = { targetMac, targetName ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (isCurrentlyConnected) {
            pendingSwitchDevice = targetMac to targetName
        } else {
            bleManager.stopSearchForCuktechChargers()
            bleManager.connectToCharger(targetMac)
            onDismiss()
        }
    }

    val context = LocalContext.current
    val btManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }
    val isBtEnabled = btManager?.adapter?.isEnabled == true

    LaunchedEffect(isBtEnabled) {
        if (isBtEnabled) {
            bleManager.startSearchForCuktechChargers(10000L)
        }
    }

    pendingSwitchDevice?.let { (targetMac, targetName) ->
        AlertDialog(
            onDismissRequest = { pendingSwitchDevice = null },
            title = {
                Text(
                    text = stringResource(R.string.xiaomi_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.xiaomi_switch_body_fmt, targetName, targetMac),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSwitchDevice = null
                        bleManager.stopSearchForCuktechChargers()
                        bleManager.switchToCharger(targetMac)
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.xiaomi_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingSwitchDevice = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.xiaomi_pair_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (!isBtEnabled) stringResource(R.string.toast_bt_disabled) else statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }

            if (!isBtEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.xiaomi_pair_bt_off_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.xiaomi_pair_bt_off_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (isScanning || authState == CuktechAuthState.SCANNING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Saved Xiaomi Cloud credentials quick-connect section
                if (savedCreds.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.xiaomi_pair_synced_header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    savedCreds.forEach { cred ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { requestConnectToDevice(cred.mac, cred.name) },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cred.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${cred.mac} · ${if (cred.hasValidToken) stringResource(R.string.xiaomi_pair_token_ready) else stringResource(R.string.xiaomi_pair_token_missing)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { requestConnectToDevice(cred.mac, cred.name) },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.action_connect))
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.xiaomi_pair_discovered_fmt, discovered.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (discovered.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.xiaomi_pair_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    discovered.forEach { dev ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { requestConnectToDevice(dev.mac, dev.name) },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${dev.mac} · ${dev.rssi} ${stringResource(R.string.unit_dbm)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = { requestConnectToDevice(dev.mac, dev.name) },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.action_connect))
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (btManager?.adapter?.isEnabled == true) {
                            bleManager.startSearchForCuktechChargers(10000L)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.toast_bt_disabled),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_rescan))
                }
            }
        }
    }
}
