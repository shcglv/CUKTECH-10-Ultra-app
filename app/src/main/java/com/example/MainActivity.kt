package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val pendingNavTab = mutableStateOf<String?>(null)
    private val pendingNfcUri = mutableStateOf<String?>(null)
    private val pendingOpenEventCenter = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()

        val cuktechManager = BleBridgeService.ensureCuktechManagersInitialized(applicationContext)
        // Synchronize DataStore preferences before the initial composition frame
        // so language, theme, AMOLED black mode, and start destination do not flicker.
        runBlocking {
            runCatching { cuktechManager.preferencesRepository.awaitLoaded() }
        }

        setContent {
            val prefs by cuktechManager.preferencesRepository.preferences.collectAsStateWithLifecycle()

            LocalizedAppContent(appLanguage = prefs.appLanguage) {
                MyApplicationTheme(
                    themeMode = prefs.themeMode,
                    useDynamicColor = prefs.useDynamicColor,
                    useAmoledBlack = prefs.darkPalette == "AMOLED_BLACK"
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        BleBridgeDashboard(
                            pendingNavTab = pendingNavTab,
                            pendingNfcUri = pendingNfcUri,
                            pendingOpenEventCenter = pendingOpenEventCenter
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the persistent notification was dismissed by user or system,
        // visiting the app restores the active telemetry/standby notification.
        BleBridgeService.getInstance()?.forceRestoreNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nav = intent?.getStringExtra("EXTRA_NAV_TAB")
        if (!nav.isNullOrBlank()) {
            pendingNavTab.value = nav
        }
        val uriData = intent?.dataString
        if (!uriData.isNullOrBlank() && uriData.startsWith("cuktech://action", ignoreCase = true)) {
            pendingNfcUri.value = uriData
        }
        if (intent?.getBooleanExtra("EXTRA_OPEN_EVENT_CENTER", false) == true) {
            pendingOpenEventCenter.value = true
        }
    }
}

@Composable
fun BleBridgeDashboard(
    pendingNavTab: MutableState<String?> = remember { mutableStateOf(null) },
    pendingNfcUri: MutableState<String?> = remember { mutableStateOf(null) },
    pendingOpenEventCenter: MutableState<Boolean> = remember { mutableStateOf(false) }
) {
    val context = LocalContext.current
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.provideFactory(
            context.applicationContext as android.app.Application
        )
    )
    CuktechAppRoot(
        viewModel = viewModel,
        pendingNavTab = pendingNavTab,
        pendingNfcUri = pendingNfcUri,
        pendingOpenEventCenter = pendingOpenEventCenter
    )
}
