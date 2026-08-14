package com.alex193a.rootmypixel.feature.main

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.ui.theme.RootMyPixelTheme

class MainActivity : ComponentActivity() {
    private val installViewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installViewModel.initShizuku()

        setContent {
            val state by installViewModel.state.collectAsStateWithLifecycle()
            val shizukuAvailable by installViewModel.shizukuAvailable.collectAsStateWithLifecycle()
            val reSukiSuInstalled by installViewModel.reSukiSuInstalled.collectAsStateWithLifecycle()
            val deviceNotSettled by installViewModel.deviceNotSettled.collectAsStateWithLifecycle()

            RootMyPixelTheme {
                MainScreen(
                    state = state,
                    shizukuAvailable = shizukuAvailable,
                    reSukiSuInstalled = reSukiSuInstalled,
                    deviceNotSettled = deviceNotSettled,
                    onRefresh = { installViewModel.refresh() },
                    onInstall = { installViewModel.install() },
                    onExportLog = { installViewModel.exportLog() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        installViewModel.refresh()
    }
}

/**
 * Per-app language picker.
 *
 * minSdk is 33, so this uses the platform LocaleManager directly rather than
 * pulling in AppCompat: the locale is stored by the system, survives reinstalls
 * of the process, and is the same setting the user gets under
 * Settings > System > Languages > App languages. Setting it recreates the
 * activity, so the UI redraws in the new language with no extra work here.
 *
 * An empty LocaleList means "follow the system", which is not the same as
 * pinning the system's current language — it keeps tracking later changes.
 */
private val LANGUAGE_TAGS = listOf(null, "en", "ja")

@Composable
private fun LanguageMenu() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val localeManager = remember(context) {
        context.getSystemService(Context.LOCALE_SERVICE) as LocaleManager
    }
    // The system may hand back a region-qualified tag ("ja-JP") even though the
    // app only declares "ja", so compare on the language subtag alone.
    var current by remember {
        mutableStateOf(localeManager.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language)
    }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Rounded.Language, contentDescription = stringResource(R.string.cd_language))
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        LANGUAGE_TAGS.forEach { tag ->
            val label = when (tag) {
                null -> stringResource(R.string.language_system)
                "ja" -> stringResource(R.string.language_ja)
                else -> stringResource(R.string.language_en)
            }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    if (tag == current) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                    }
                },
                onClick = {
                    expanded = false
                    current = tag
                    localeManager.applicationLocales =
                        if (tag == null) LocaleList.getEmptyLocaleList()
                        else LocaleList.forLanguageTags(tag)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: InstallUiState,
    shizukuAvailable: Boolean,
    reSukiSuInstalled: Boolean,
    deviceNotSettled: Boolean,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
    onExportLog: () -> Unit,
) {
    val context = LocalContext.current

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelMedium.copy(
                                textDecoration = TextDecoration.Underline,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/BuSung-dev/Root-My-Galaxy".toUri())
                                context.startActivity(intent)
                            },
                        )
                    }
                },
                actions = {
                    if (state.log.isNotBlank()) {
                        IconButton(onClick = onExportLog) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.cd_export_log),
                            )
                        }
                    }
                    LanguageMenu()
                    IconButton(onClick = onRefresh, enabled = !state.busy) {
                        if (state.busy) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DeveloperSocialCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            // Uptime status
            UptimeErrorCard(exceeded = deviceNotSettled)

            if (deviceNotSettled) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Shizuku status
            ShizukuStatusCard(available = shizukuAvailable)

            Spacer(modifier = Modifier.height(8.dp))

            // ReSukiSU Manager status
            ReSukiSuManagerCard(
                installed = reSukiSuInstalled,
                context = LocalContext.current,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = when (state.phase) {
                        InstallPhase.Failed -> MaterialTheme.colorScheme.errorContainer
                        InstallPhase.Installed -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = when (state.phase) {
                            InstallPhase.Installed -> stringResource(R.string.status_ksu_active)
                            InstallPhase.Ready -> stringResource(R.string.status_not_installed)
                            InstallPhase.Failed -> state.message
                            else -> stringResource(R.string.status_checking)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Device info
                    if (state.probeOutput.isNotEmpty()) {
                        Text(
                            text = state.probeOutput.take(800),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Install button
            Button(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = state.phase == InstallPhase.Ready || state.phase == InstallPhase.Installed,
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Text(
                    text = stringResource(
                        if (state.phase == InstallPhase.Installed) R.string.action_reinstall
                        else R.string.action_install
                    ),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log preview
            if (state.log.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.install_live_progress),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(state.log))
                            Toast.makeText(
                                context,
                                R.string.copied_to_clipboard,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_log),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.log.takeLast(2000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UptimeErrorCard(exceeded: Boolean) {
    if (!exceeded) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.uptime_error_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.uptime_error_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShizukuStatusCard(available: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (available)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Icon(
                imageVector = if (available) Icons.Rounded.Shield else Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (available)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (available) R.string.shizuku_status_active
                    else R.string.shizuku_status_inactive
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ReSukiSuManagerCard(installed: Boolean, context: android.content.Context) {
    if (installed) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.resuki_missing_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.resuki_missing_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = "https://resukisu.github.io/guide/install.html#Get-manager".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Rounded.OpenInBrowser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.resuki_missing_action))
            }
        }
    }
}

@Composable
private fun DeveloperSocialCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxSize(),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.credits_developed_by),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = "alex193a",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Threads Pill Chip
                Surface(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://www.threads.com/@alex193a".toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_threads),
                            contentDescription = "Threads",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Threads",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Twitter Pill Chip
                Surface(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://twitter.com/alex193a".toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_twitter),
                            contentDescription = "Twitter",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Twitter",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

