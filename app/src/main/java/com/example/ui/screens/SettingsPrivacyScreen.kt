package com.example.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import com.example.R
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.MedicalIdProfile
import com.example.domain.SosAudioClipMetadata
import com.example.domain.SosAudioStatus
import com.example.ui.components.PastelCard
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.WarmMistBackground
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimal, clean Settings Screen for SoloSignal AI.
 * Shows only essential options:
 * - Account info & sign out
 * - Alert preferences (GPS sharing, vibration, SOS 15s rolling audio buffer, countdown timer, emergency buzzer test)
 * - Required Android permissions
 * - Data reset option
 */
@Composable
fun SettingsPrivacyScreen(
    countdownSeconds: Int,
    vibrationEnabled: Boolean,
    optionalLocationEnabled: Boolean,
    hasAccelerometer: Boolean,
    deliveryStatus: DeliveryConfigStatus,
    deliveryStatusDetail: String,
    googleChatConfig: GoogleChatAlertConfig,
    userEmail: String?,
    onCountdownSecondsChanged: (Int) -> Unit,
    onVibrationEnabledChanged: (Boolean) -> Unit,
    onOptionalLocationChanged: (Boolean) -> Unit,
    onDeleteAllPersonalData: () -> Unit,
    onSignOut: () -> Unit,
    onTestEmergencyBuzzer: (() -> Boolean)? = null,
    onStopEmergencyBuzzer: (() -> Unit)? = null,
    sosAudioEnabled: Boolean = false,
    sosAudioConsentGranted: Boolean = false,
    sosAudioStatus: SosAudioStatus = SosAudioStatus.DISABLED,
    sosAudioBufferedSeconds: Double = 0.0,
    sosAudioRetentionDays: Int = 7,
    lastCapturedAudioClip: SosAudioClipMetadata? = null,
    onSosAudioEnabledChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    onMicrophonePermissionResult: (Boolean) -> Unit = {},
    onDeleteStoredSosAudio: () -> Unit = {},
    medicalIdProfile: MedicalIdProfile = MedicalIdProfile(),
    onOpenMedicalId: () -> Unit = {},
    onSaveMedicalIdProfile: (MedicalIdProfile) -> Unit = {},
    onClearMedicalIdProfile: () -> Unit = {},
    selectedLanguageCode: String = "en",
    onLanguageSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var isBuzzerTesting by remember { mutableStateOf(false) }
    var showSosAudioConsentDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var audioClipDeletedMessage by remember { mutableStateOf<String?>(null) }

    var hasLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasNotifPerm by remember {
        mutableStateOf(
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }) && NotificationManagerCompat.from(context).areNotificationsEnabled()
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPerm = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
        onMicrophonePermissionResult(granted)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openAndroidAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("settings_privacy_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal
                    )
                    Text(
                        text = "Essential safety and alert preferences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate
                    )
                }
            }

            // 1. Account Card
            PastelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            SectionEyebrow(text = "ACCOUNT")
                            Text(
                                text = userEmail ?: "Google Account",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out")
                    }
                }


            }

            // 1b. Language Selection Card
            val currentLanguage = remember(selectedLanguageCode) {
                com.example.util.LocaleHelper.getLanguageByCode(selectedLanguageCode)
            }

            PastelCard(
                modifier = Modifier.testTag("settings_language_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                        SectionEyebrow(text = stringResource(R.string.settings_language_label).uppercase())
                    }
                    StatusPillBadge(
                        label = currentLanguage.nativeName,
                        containerColor = MintSoftSurface,
                        contentColor = DeepCharcoal,
                        modifier = Modifier.testTag("current_language_badge")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.settings_language_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { showLanguageDialog = true },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("settings_change_language_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = DeepCharcoal
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${currentLanguage.nativeName} (${currentLanguage.englishName})",
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 1c. Voluntary Medical ID — User Health Profile Card
            PastelCard(
                modifier = Modifier.testTag("settings_medical_id_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MedicalInformation,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                        SectionEyebrow(text = "MEDICAL ID (USER HEALTH PROFILE)")
                    }
                    StatusPillBadge(
                        label = if (medicalIdProfile.isConfigured) "Configured" else "Optional",
                        containerColor = if (medicalIdProfile.isConfigured) MintSoftSurface else LavenderSoftSurface,
                        modifier = Modifier.testTag("settings_medical_id_badge")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (medicalIdProfile.isConfigured) {
                        buildString {
                            if (medicalIdProfile.fullName.isNotBlank()) {
                                append("${medicalIdProfile.fullName} • ")
                            }
                            if (medicalIdProfile.bloodType.isNotBlank()) {
                                append("Blood: ${medicalIdProfile.bloodType} • ")
                            }
                            append("${medicalIdProfile.knownConditions.size} condition(s), ${medicalIdProfile.medications.size} medication(s), ${medicalIdProfile.allergies.size} allerg(ies)")
                        }
                    } else {
                        "Voluntarily store conditions, blood thinners/medications, allergies, and blood type for post-fall AI Health Cross-Checks."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use Medical ID in AI Health Cross-Check",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Checks post-fall symptoms against your stored conditions and medications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = medicalIdProfile.allowAiCrossCheck,
                        onCheckedChange = { enabled ->
                            onSaveMedicalIdProfile(medicalIdProfile.copy(allowAiCrossCheck = enabled))
                        },
                        modifier = Modifier.testTag("settings_medical_id_ai_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Include Medical ID in SOS Alerts",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Attaches your consented Medical ID summary to emergency SOS messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = medicalIdProfile.includeInEmergencyAlerts,
                        onCheckedChange = { enabled ->
                            onSaveMedicalIdProfile(medicalIdProfile.copy(includeInEmergencyAlerts = enabled))
                        },
                        modifier = Modifier.testTag("settings_medical_id_sos_switch")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenMedicalId,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 46.dp)
                            .testTag("settings_open_medical_id_button")
                    ) {
                        Text(
                            text = if (medicalIdProfile.isConfigured) "Edit Medical ID" else "Set Up Medical ID",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (medicalIdProfile.isConfigured) {
                        OutlinedButton(
                            onClick = onClearMedicalIdProfile,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 46.dp)
                                .testTag("settings_clear_medical_id_button")
                        ) {
                            Text("Clear", color = CoralUrgentHighlight)
                        }
                    }
                }


            }

            // 2. Alert Preferences Card
            PastelCard {
                SectionEyebrow(text = "ALERT PREFERENCES")
                Spacer(modifier = Modifier.height(10.dp))

                // GPS Location sharing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Share GPS in Alerts",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Attaches live Google Maps location to SOS alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = optionalLocationEnabled,
                        onCheckedChange = onOptionalLocationChanged,
                        modifier = Modifier.testTag("settings_location_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(12.dp))

                // Vibration on alert
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Vibrate on Alert",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Vibrates during check-ins and emergency alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = onVibrationEnabledChanged,
                        modifier = Modifier.testTag("vibration_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(12.dp))

                // Emergency Contact Buzzer Tone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Emergency Alert Sound",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                            StatusPillBadge(
                                label = "Loud Buzzer",
                                containerColor = CoralSoftSurface
                            )
                        }
                        Text(
                            text = "Sharp, repetitive electronic emergency buzzer on the contact's phone (replaces default app chime).",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (!isBuzzerTesting) {
                            val started = onTestEmergencyBuzzer?.invoke() ?: false
                            if (started) isBuzzerTesting = true
                        } else {
                            onStopEmergencyBuzzer?.invoke()
                            isBuzzerTesting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBuzzerTesting) CoralUrgentHighlight else DeepCharcoal,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("test_emergency_buzzer_button")
                ) {
                    Icon(
                        imageVector = if (isBuzzerTesting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBuzzerTesting) "Stop Buzzer Preview" else "Test Emergency Buzzer Sound",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(12.dp))

                // Countdown duration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Check-In Countdown",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal
                    )
                    StatusPillBadge(
                        label = "${countdownSeconds}s",
                        containerColor = LavenderSoftSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Time to confirm you're okay before alert sends automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )
                Slider(
                    value = countdownSeconds.toFloat(),
                    onValueChange = { onCountdownSecondsChanged(it.roundToInt()) },
                    valueRange = 10f..45f,
                    steps = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("countdown_duration_slider")
                )
            }

            // 2b. SOS Audio Recording (15-Second Rolling Buffer) Card
            PastelCard(
                modifier = Modifier.testTag("sos_audio_settings_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionEyebrow(text = "SOS AUDIO RECORDING (15S BUFFER)")
                    val badgeColor = when (sosAudioStatus) {
                        SosAudioStatus.AUDIO_MONITORING_ENABLED,
                        SosAudioStatus.SOS_AUDIO_CAPTURED,
                        SosAudioStatus.SOS_EMAIL_SENT -> MintSoftSurface
                        SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED,
                        SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE,
                        SosAudioStatus.AUDIO_UPLOAD_FAILED,
                        SosAudioStatus.SOS_EMAIL_FAILED -> CoralSoftSurface
                        SosAudioStatus.UPLOAD_IN_PROGRESS -> LavenderSoftSurface
                        SosAudioStatus.DISABLED -> WarmMistBackground
                    }
                    StatusPillBadge(
                        label = sosAudioStatus.label,
                        containerColor = badgeColor,
                        modifier = Modifier.testTag("sos_audio_status_badge")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SOS Audio Recording",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Keeps a rolling 15-second audio buffer in memory only when enabled. Uploaded and emailed to your emergency contact only if an SOS countdown expires without confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = sosAudioEnabled,
                        onCheckedChange = { wantEnabled ->
                            if (wantEnabled) {
                                if (!sosAudioConsentGranted) {
                                    showSosAudioConsentDialog = true
                                } else {
                                    onSosAudioEnabledChange(true, true)
                                    if (!hasMicPerm) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            } else {
                                onSosAudioEnabledChange(false, sosAudioConsentGranted)
                            }
                        },
                        modifier = Modifier.testTag("sos_audio_enable_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = WarmMistBackground,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = sosAudioStatus.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepCharcoal,
                            modifier = Modifier.testTag("sos_audio_status_detail")
                        )
                        if (sosAudioEnabled && sosAudioStatus == SosAudioStatus.AUDIO_MONITORING_ENABLED) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Rolling buffer active: %.1fs / 15.0s in RAM (auto-overwriting older audio; Retention: %d days for SOS clips)",
                                    sosAudioBufferedSeconds,
                                    sosAudioRetentionDays
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = NeutralSlate,
                                modifier = Modifier.testTag("sos_audio_buffered_seconds_text")
                            )
                        }
                        if (lastCapturedAudioClip != null) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Last SOS clip: %.1fs (%s)%s",
                                    lastCapturedAudioClip.durationSeconds,
                                    if (lastCapturedAudioClip.isPartialBuffer) "partial buffer" else "full 15s pre-trigger buffer",
                                    if (!lastCapturedAudioClip.downloadUrl.isNullOrBlank()) " • Cloud backup stored" else ""
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = DeepCharcoal,
                                modifier = Modifier.testTag("sos_audio_last_clip_summary")
                            )
                        }
                        val deletedMsg = audioClipDeletedMessage
                        if (!deletedMsg.isNullOrBlank()) {
                            Text(
                                text = deletedMsg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                        }
                    }
                }

                if (sosAudioEnabled && (!hasMicPerm || sosAudioStatus == SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("grant_microphone_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Grant Microphone Permission", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        onDeleteStoredSosAudio()
                        audioClipDeletedMessage = "Cleared rolling buffer and deleted stored SOS audio clips."
                    },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("delete_sos_audio_clips_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Stored SOS Audio Clips")
                }
            }

            // 3. Android System Permissions Card
            PastelCard {
                SectionEyebrow(text = "SYSTEM PERMISSIONS")
                Spacer(modifier = Modifier.height(10.dp))

                // Location Permission
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = DeepCharcoal, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Location Access", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = DeepCharcoal)
                            Text("Needed for GPS coordinates in alerts.", style = MaterialTheme.typography.bodySmall, color = NeutralSlate)
                        }
                    }
                    if (hasLocationPerm) {
                        StatusPillBadge(label = "Granted", containerColor = MintSoftSurface, modifier = Modifier.testTag("settings_location_permission_badge"))
                    } else {
                        Button(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.testTag("settings_request_location_button")
                        ) {
                            Text("Allow", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(10.dp))

                // Microphone Permission (for optional SOS Audio Recording)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = null, tint = DeepCharcoal, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Microphone Access", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = DeepCharcoal)
                            Text("Optional: used only when SOS Audio Recording is enabled.", style = MaterialTheme.typography.bodySmall, color = NeutralSlate)
                        }
                    }
                    if (hasMicPerm) {
                        StatusPillBadge(label = "Granted", containerColor = MintSoftSurface, modifier = Modifier.testTag("settings_microphone_permission_badge"))
                    } else {
                        Button(
                            onClick = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.testTag("settings_request_microphone_button")
                        ) {
                            Text("Allow", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(10.dp))

                // Notifications Permission
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = DeepCharcoal, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Emergency Notifications", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = DeepCharcoal)
                            Text("Needed for urgent alert pop-ups.", style = MaterialTheme.typography.bodySmall, color = NeutralSlate)
                        }
                    }
                    if (hasNotifPerm) {
                        StatusPillBadge(label = "Granted", containerColor = MintSoftSurface, modifier = Modifier.testTag("settings_notification_permission_badge"))
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAndroidAppSettings()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.testTag("settings_open_app_settings_button")
                        ) {
                            Text("Allow", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Full-Screen Emergency Alerts (Android 14+ special access check)
                val canUseFullScreen = remember(hasNotifPerm) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.canUseFullScreenIntent() == true
                    } else {
                        true
                    }
                }
                HorizontalDivider(color = HairlineDivider, thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = DeepCharcoal, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Full-Screen Emergency Alerts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = DeepCharcoal)
                            Text("Wakes display & shows emergency screen over lock screen.", style = MaterialTheme.typography.bodySmall, color = NeutralSlate)
                        }
                    }
                    if (canUseFullScreen) {
                        StatusPillBadge(label = "Granted", containerColor = MintSoftSurface, modifier = Modifier.testTag("settings_fullscreen_intent_permission_badge"))
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        openAndroidAppSettings()
                                    }
                                } else {
                                    openAndroidAppSettings()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.testTag("settings_open_fullscreen_settings_button")
                        ) {
                            Text("Allow", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 4. Data & Privacy
            PastelCard(containerColor = WarmMistBackground) {
                SectionEyebrow(text = "DATA & PRIVACY")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sensor data is processed entirely on-device. Alerts are securely sent only to your chosen contact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (!confirmDeleteAll) {
                    OutlinedButton(
                        onClick = { confirmDeleteAll = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralUrgentHighlight),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("request_delete_all_data_button")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete All Personal Data")
                    }
                } else {
                    Surface(
                        color = CoralSoftSurface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Delete contact, settings, and stored alert history?",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onDeleteAllPersonalData()
                                        confirmDeleteAll = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DeepCharcoal,
                                        contentColor = Color.White,
                                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                        disabledContentColor = Color.White.copy(alpha = 0.9f)
                                    ),
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("confirm_delete_all_data_button")
                                ) {
                                    Text("Yes, Delete", color = Color.White, maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    onClick = { confirmDeleteAll = false },
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }


            }
        }
    }

    if (showSosAudioConsentDialog) {
        AlertDialog(
            onDismissRequest = { showSosAudioConsentDialog = false },
            title = {
                Text(
                    text = "Enable SOS Audio Recording?",
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "When enabled, SoloSignal AI keeps a temporary 15-second rolling audio buffer in device memory while safety monitoring is active.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DeepCharcoal
                    )
                    Text(
                        text = "• Audio older than 15 seconds is continuously overwritten in RAM and is never saved or uploaded during normal operation.\n" +
                            "• If a possible fall is detected and you tap \"I'm OK\", the temporary audio buffer is immediately discarded.\n" +
                            "• Only if the countdown expires without confirmation (or you tap \"Send SOS Now\") is the 15-second recording finalized, uploaded securely to Firebase Storage, and sent in the emergency email to your trusted contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSosAudioConsentDialog = false
                        onSosAudioEnabledChange(true, true)
                        if (!hasMicPerm) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("sos_audio_consent_accept_button")
                ) {
                    Text("Agree & Enable", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSosAudioConsentDialog = false },
                    modifier = Modifier.testTag("sos_audio_consent_cancel_button")
                ) {
                    Text("Cancel", color = DeepCharcoal)
                }
            },
            modifier = Modifier.testTag("sos_audio_consent_dialog")
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_select_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    com.example.util.LocaleHelper.SUPPORTED_LANGUAGES.forEach { lang ->
                        val isSelected = lang.code.equals(selectedLanguageCode, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MintSoftSurface else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageSelected(lang.code)
                                    showLanguageDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .testTag("language_option_${lang.code}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = lang.nativeName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = DeepCharcoal
                                    )
                                    Text(
                                        text = lang.englishName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeutralSlate
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MintConfirm,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showLanguageDialog = false },
                    modifier = Modifier.testTag("dismiss_language_dialog_button")
                ) {
                    Text(stringResource(R.string.action_cancel), color = DeepCharcoal)
                }
            },
            containerColor = CardSurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("language_selection_dialog")
        )
    }
}
