package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.BleBridgeService
import com.example.R
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
class CuktechWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val initialConfig = WidgetConfigStore.loadConfig(this, appWidgetId)
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(applicationContext)
        val prefs = mgr.preferencesRepository.preferences.value

        setContent {
            val scope = rememberCoroutineScope()
            var selectedPorts by remember { mutableStateOf(initialConfig.emphasizedPorts) }
            var showSparkline by remember { mutableStateOf(initialConfig.showSparkline) }
            var showSessionWh by remember { mutableStateOf(initialConfig.showSessionWh) }
            var tapDestination by remember { mutableStateOf(initialConfig.tapDestination) }

            LocalizedAppContent(appLanguage = prefs.appLanguage) {
                MyApplicationTheme(
                    themeMode = prefs.themeMode,
                    useDynamicColor = true,
                    useAmoledBlack = prefs.darkPalette == "AMOLED_BLACK"
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.widget_config_title)) }
                            )
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                                .testTag("widget_config_screen"),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.widget_config_ports_label),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            1 to stringResource(R.string.port_name_c1),
                                            2 to stringResource(R.string.port_name_c2),
                                            3 to stringResource(R.string.port_name_c3),
                                            4 to stringResource(R.string.port_name_a)
                                        ).forEach { (pid, label) ->
                                            val isSel = pid in selectedPorts
                                            FilterChip(
                                                selected = isSel,
                                                onClick = {
                                                    selectedPorts = if (isSel) {
                                                        if (selectedPorts.size > 1) selectedPorts - pid else selectedPorts
                                                    } else {
                                                        selectedPorts + pid
                                                    }
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.widget_config_sparkline), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showSparkline, onCheckedChange = { showSparkline = it })
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.widget_config_session_wh), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showSessionWh, onCheckedChange = { showSessionWh = it })
                                    }
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.widget_config_tap_dest),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "OVERVIEW" to stringResource(R.string.nav_overview),
                                            "PORTS" to stringResource(R.string.nav_ports),
                                            "ANALYTICS" to stringResource(R.string.nav_analytics),
                                            "SETTINGS" to stringResource(R.string.nav_settings)
                                        ).forEach { (dest, label) ->
                                            FilterChip(
                                                selected = tapDestination == dest,
                                                onClick = { tapDestination = dest },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { finish() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                Button(
                                    onClick = {
                                        val cfg = WidgetInstanceConfig(
                                            emphasizedPorts = selectedPorts,
                                            showSparkline = showSparkline,
                                            showSessionWh = showSessionWh,
                                            tapDestination = tapDestination
                                        )
                                        WidgetConfigStore.saveConfig(this@CuktechWidgetConfigActivity, appWidgetId, cfg)
                                        scope.launch {
                                            CuktechWidgetUpdater.scheduleReactiveUpdate(applicationContext, force = true)
                                            val resultValue = Intent().apply {
                                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                            }
                                            setResult(RESULT_OK, resultValue)
                                            finish()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("widget_config_save_button")
                                ) {
                                    Text(stringResource(R.string.action_save))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
