package com.example.emergency

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Theme constants consistent with SoloSignal AI aesthetic
private val EmergencyUrgentRed = Color(0xFFDC2626)
private val EmergencyUrgentRedBg = Color(0xFFFEE2E2)
private val DeepCharcoal = Color(0xFF1E293B)
private val NeutralSlate = Color(0xFF64748B)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val BorderGrey = Color(0xFFE2E8F0)
private val InfoBlueBg = Color(0xFFEFF6FF)
private val InfoBlueBorder = Color(0xFFBFDBFE)
private val WarningAmberBg = Color(0xFFFFFBEB)
private val WarningAmberBorder = Color(0xFFFDE68A)

/**
 * Emergency calling confirmation modal dialog.
 *
 * Implements all requirements:
 * 1. Shows detected emergency service and number before opening dialer.
 * 2. Informs the user that the phone's dialer will open and they must press Call.
 * 3. Never claims an emergency call was placed merely because dialer was opened.
 * 4. Allows switching service (Ambulance, Police, Fire, General).
 * 5. Allows manual country selection or custom number entry.
 * 6. Handles edge cases (no dialer app, unknown country, roaming).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyCallConfirmationDialog(
    initialServiceType: EmergencyServiceType = EmergencyServiceType.AMBULANCE,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedService by remember { mutableStateOf(initialServiceType) }
    var selectedCountryIso by remember { mutableStateOf<String?>(null) }
    var customNumberInput by remember { mutableStateOf("") }
    var isEditingCountryOrNumber by remember { mutableStateOf(false) }
    var dialerFailureMessage by remember { mutableStateOf<String?>(null) }

    // Resolve number dynamically based on state
    val resolved = remember(selectedService, selectedCountryIso, customNumberInput) {
        EmergencyCallManager.resolveEmergencyNumber(
            context = context,
            preferredService = selectedService,
            userSelectedCountryIso = selectedCountryIso,
            userCustomNumber = customNumberInput.ifBlank { null }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(2.dp, DeepCharcoal, RoundedCornerShape(24.dp))
                .testTag("emergency_call_confirmation_dialog"),
            color = SurfaceWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = EmergencyUrgentRedBg,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = EmergencyUrgentRed,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Emergency Call",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = DeepCharcoal
                            )
                            Text(
                                text = "Review & Prepare Phone Dialer",
                                fontSize = 13.sp,
                                color = NeutralSlate
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("emergency_dialog_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close emergency call dialog",
                            tint = DeepCharcoal
                        )
                    }
                }

                // Service Category Selector (Ambulance / Medical prioritized)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SELECT SERVICE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeutralSlate,
                        letterSpacing = 0.5.sp
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EmergencyServiceType.values().forEach { serviceType ->
                            val isSelected = selectedService == serviceType
                            val icon = when (serviceType) {
                                EmergencyServiceType.AMBULANCE -> Icons.Default.MedicalServices
                                EmergencyServiceType.POLICE -> Icons.Default.LocalPolice
                                EmergencyServiceType.FIRE -> Icons.Default.LocalFireDepartment
                                EmergencyServiceType.GENERAL -> Icons.Default.Call
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) DeepCharcoal else SurfaceWhite,
                                border = BorderStroke(
                                    width = 1.5.dp,
                                    color = if (isSelected) DeepCharcoal else BorderGrey
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedService = serviceType }
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("emergency_service_tab_${serviceType.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else DeepCharcoal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = serviceType.shortLabel,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else DeepCharcoal
                                    )
                                }
                            }
                        }
                    }
                }

                // Number & Country Display Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (resolved.isDetermined) EmergencyUrgentRedBg else WarningAmberBg,
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (resolved.isDetermined) EmergencyUrgentRed.copy(alpha = 0.5f) else WarningAmberBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_target_number_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = selectedService.displayLabel.uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (resolved.isDetermined) EmergencyUrgentRed else DeepCharcoal,
                            letterSpacing = 0.5.sp
                        )

                        if (resolved.isDetermined) {
                            Text(
                                text = resolved.number,
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                                color = DeepCharcoal,
                                modifier = Modifier.testTag("emergency_number_display_text")
                            )
                        } else {
                            Text(
                                text = "Number Unspecified",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal,
                                modifier = Modifier.testTag("emergency_number_unspecified_text")
                            )
                        }

                        // Country & Source Badge
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = SurfaceWhite,
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = DeepCharcoal,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (resolved.countryIso.isNotBlank() && resolved.countryIso != "CUSTOM") {
                                        "${resolved.countryName} (${resolved.countryIso})"
                                    } else {
                                        resolved.countryName
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DeepCharcoal
                                )
                            }
                        }

                        Text(
                            text = resolved.explanation,
                            fontSize = 12.sp,
                            color = NeutralSlate,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Change Country / Enter Custom Number Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditingCountryOrNumber) "Country / Custom Number:" else "Need a different country or number?",
                        fontSize = 13.sp,
                        color = NeutralSlate
                    )
                    TextButton(
                        onClick = { isEditingCountryOrNumber = !isEditingCountryOrNumber },
                        modifier = Modifier.testTag("toggle_custom_emergency_number_button")
                    ) {
                        Icon(
                            imageVector = if (isEditingCountryOrNumber) Icons.Default.Close else Icons.Default.Edit,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEditingCountryOrNumber) "Done" else "Change",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    }
                }

                // Expandable Country Picker & Custom Number Form
                if (isEditingCountryOrNumber || !resolved.isDetermined) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = InfoBlueBg,
                        border = BorderStroke(1.dp, InfoBlueBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Select Country Profile",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )

                            // Quick Country Chips (UAE, US, UK, Canada, Australia, Germany, France, India)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "AE" to "UAE (998)",
                                    "US" to "USA (911)",
                                    "GB" to "UK (999)",
                                    "CA" to "Canada (911)",
                                    "AU" to "Australia (000)",
                                    "DE" to "Germany (112)",
                                    "FR" to "France (15)",
                                    "IN" to "India (108)",
                                    "SA" to "Saudi (997)",
                                    "SG" to "Singapore (995)"
                                ).forEach { (iso, label) ->
                                    val isCurrent = (selectedCountryIso ?: resolved.countryIso) == iso
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isCurrent) DeepCharcoal else SurfaceWhite,
                                        border = BorderStroke(1.dp, if (isCurrent) DeepCharcoal else BorderGrey),
                                        modifier = Modifier
                                            .clickable {
                                                selectedCountryIso = iso
                                                customNumberInput = ""
                                            }
                                            .testTag("emergency_country_chip_$iso")
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCurrent) Color.White else DeepCharcoal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            // Manual Number Input Field
                            OutlinedTextField(
                                value = customNumberInput,
                                onValueChange = { customNumberInput = it },
                                label = { Text("Or enter exact local emergency number") },
                                placeholder = { Text("e.g. 998, 911, 112") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        isEditingCountryOrNumber = false
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DeepCharcoal,
                                    unfocusedBorderColor = BorderGrey,
                                    focusedContainerColor = SurfaceWhite,
                                    unfocusedContainerColor = SurfaceWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_emergency_number_input")
                            )
                        }
                    }
                }

                // Failure message banner if phone app could not be opened
                dialerFailureMessage?.let { failureReason ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = EmergencyUrgentRedBg,
                        border = BorderStroke(1.dp, EmergencyUrgentRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialer_failure_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = EmergencyUrgentRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = failureReason,
                                fontSize = 13.sp,
                                color = DeepCharcoal,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Android Security Explanation Box
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = InfoBlueBg,
                    border = BorderStroke(1.dp, InfoBlueBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_security_notice_box")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF1D4ED8),
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Android Security Notice",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1D4ED8)
                            )
                            Text(
                                text = "Tapping below will open your phone's dialer with ${if (resolved.isDetermined) resolved.number else "your chosen number"} ready. You must tap the green Call button in the dialer to place the call. SoloSignal AI never silently places calls.",
                                fontSize = 12.sp,
                                color = DeepCharcoal,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Action Buttons: Prepare Dialer & Cancel
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!resolved.isDetermined) {
                                isEditingCountryOrNumber = true
                                return@Button
                            }
                            dialerFailureMessage = null
                            val result = EmergencyCallManager.openDialerWithEmergencyNumber(
                                context = context,
                                number = resolved.number
                            )
                            when (result) {
                                is EmergencyDialerResult.DialerOpened -> {
                                    onDismiss()
                                }
                                is EmergencyDialerResult.Failure -> {
                                    dialerFailureMessage = result.reason
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmergencyUrgentRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .testTag("confirm_open_emergency_dialer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (resolved.isDetermined) {
                                "Open Dialer (${resolved.number})"
                            } else {
                                "Select Country / Number"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, DeepCharcoal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cancel_emergency_dialer_button")
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    }
                }
            }
        }
    }
}
