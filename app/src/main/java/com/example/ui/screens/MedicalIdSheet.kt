package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.MedicalIdProfile
import com.example.R
import com.example.ui.components.PastelCard
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.WarmMistBackground

/**
 * Voluntary Medical ID — User Health Profile editor and viewer screen.
 * Accessible from both the Home dashboard and Settings & Privacy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicalIdSheet(
    currentProfile: MedicalIdProfile,
    onSaveProfile: (MedicalIdProfile) -> Unit,
    onClearProfile: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler {
        onClose()
    }

    var fullName by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.fullName)
    }
    var dateOfBirth by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.dateOfBirth)
    }
    var ageOrGroup by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.ageOrGroup)
    }
    var bloodType by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.bloodType)
    }
    var selectedConditions by remember(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.knownConditions)
    }
    var customConditionInput by rememberSaveable { mutableStateOf("") }

    var selectedMedications by remember(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.medications)
    }
    var customMedicationInput by rememberSaveable { mutableStateOf("") }

    var selectedAllergies by remember(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.allergies)
    }
    var customAllergyInput by rememberSaveable { mutableStateOf("") }

    var mobilityOrFallHistory by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.mobilityOrFallHistory)
    }
    var emergencyNotes by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.emergencyNotes)
    }
    var allowAiCrossCheck by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.allowAiCrossCheck)
    }
    var includeInEmergencyAlerts by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.includeInEmergencyAlerts)
    }
    var syncToCloudFirestore by rememberSaveable(currentProfile.updatedAtEpochMillis) {
        mutableStateOf(currentProfile.syncToCloudFirestore)
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .testTag("medical_id_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MedicalInformation,
                        contentDescription = null,
                        tint = DeepCharcoal,
                        modifier = Modifier.size(24.dp)
                    )
                    StatusPillBadge(
                        label = "Voluntary Medical ID",
                        containerColor = LavenderSoftSurface
                    )
                }

                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("close_medical_id_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Medical ID",
                        tint = DeepCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.medical_id_close), color = DeepCharcoal, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = stringResource(R.string.medical_id_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = DeepCharcoal
            )

            Text(
                text = "All fields are optional. When enabled, SoloSignal AI cross-checks your post-fall symptoms against your Medical ID (such as blood thinners, diabetes, or bone fragility) to provide safer post-fall guidance.",
                style = MaterialTheme.typography.bodySmall,
                color = NeutralSlate
            )

            // 1. Personal Details & Blood Type
            PastelCard(containerColor = CardSurfaceWhite) {
                SectionEyebrow(text = "PERSONAL & BLOOD TYPE")
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text(stringResource(R.string.med_id_full_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("medical_id_full_name_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dateOfBirth,
                        onValueChange = { dateOfBirth = it },
                        label = { Text(stringResource(R.string.med_id_dob)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("medical_id_dob_input")
                    )
                    OutlinedTextField(
                        value = ageOrGroup,
                        onValueChange = { ageOrGroup = it },
                        label = { Text(stringResource(R.string.med_id_age)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.7f)
                            .testTag("medical_id_age_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.med_id_blood_type),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MedicalIdProfile.BLOOD_TYPES.forEach { bt ->
                        val selected = bloodType == bt
                        MedicalTagChip(
                            label = bt,
                            selected = selected,
                            onClick = { bloodType = if (selected) "" else bt },
                            testTag = "blood_type_chip_${bt.lowercase()}"
                        )
                    }
                }
            }

            // 2. Known Medical Conditions
            PastelCard(containerColor = CardSurfaceWhite) {
                SectionEyebrow(text = "KNOWN MEDICAL CONDITIONS")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select any conditions that apply or add your own:",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )
                Spacer(modifier = Modifier.height(8.dp))

                val allConditionOptions = (MedicalIdProfile.SUGGESTED_CONDITIONS + selectedConditions).distinct()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allConditionOptions.forEach { cond ->
                        val selected = selectedConditions.contains(cond)
                        MedicalTagChip(
                            label = cond,
                            selected = selected,
                            onClick = {
                                selectedConditions = if (selected) {
                                    selectedConditions - cond
                                } else {
                                    selectedConditions + cond
                                }
                            },
                            testTag = "condition_chip_${cond.take(16).lowercase().replace(" ", "_")}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customConditionInput,
                        onValueChange = { customConditionInput = it },
                        label = { Text(stringResource(R.string.med_id_add_condition)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_condition_input")
                    )
                    Button(
                        onClick = {
                            val clean = customConditionInput.trim()
                            if (clean.isNotBlank() && !selectedConditions.contains(clean)) {
                                selectedConditions = selectedConditions + clean
                                customConditionInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_custom_condition_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add condition", tint = Color.White)
                    }
                }
            }

            // 3. Current Medications (Especially Blood Thinners, Insulin, BP Meds)
            PastelCard(containerColor = CardSurfaceWhite) {
                SectionEyebrow(text = "CURRENT MEDICATIONS")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Include blood thinners/anticoagulants, diabetes medications, or blood pressure medications:",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )
                Spacer(modifier = Modifier.height(8.dp))

                val allMedicationOptions = (MedicalIdProfile.SUGGESTED_MEDICATIONS + selectedMedications).distinct()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allMedicationOptions.forEach { med ->
                        val selected = selectedMedications.contains(med)
                        MedicalTagChip(
                            label = med,
                            selected = selected,
                            onClick = {
                                selectedMedications = if (selected) {
                                    selectedMedications - med
                                } else {
                                    selectedMedications + med
                                }
                            },
                            testTag = "medication_chip_${med.take(16).lowercase().replace(" ", "_")}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customMedicationInput,
                        onValueChange = { customMedicationInput = it },
                        label = { Text(stringResource(R.string.med_id_add_medication)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_medication_input")
                    )
                    Button(
                        onClick = {
                            val clean = customMedicationInput.trim()
                            if (clean.isNotBlank() && !selectedMedications.contains(clean)) {
                                selectedMedications = selectedMedications + clean
                                customMedicationInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_custom_medication_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add medication", tint = Color.White)
                    }
                }
            }

            // 4. Allergies, Mobility & Emergency Notes
            PastelCard(containerColor = CardSurfaceWhite) {
                SectionEyebrow(text = "ALLERGIES, MOBILITY & EMERGENCY NOTES")
                Spacer(modifier = Modifier.height(8.dp))

                val allAllergyOptions = (MedicalIdProfile.SUGGESTED_ALLERGIES + selectedAllergies).distinct()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allAllergyOptions.forEach { allergy ->
                        val selected = selectedAllergies.contains(allergy)
                        MedicalTagChip(
                            label = allergy,
                            selected = selected,
                            onClick = {
                                selectedAllergies = if (selected) {
                                    selectedAllergies - allergy
                                } else {
                                    selectedAllergies + allergy
                                }
                            },
                            testTag = "allergy_chip_${allergy.take(14).lowercase().replace(" ", "_")}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customAllergyInput,
                        onValueChange = { customAllergyInput = it },
                        label = { Text(stringResource(R.string.med_id_add_allergy)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_allergy_input")
                    )
                    Button(
                        onClick = {
                            val clean = customAllergyInput.trim()
                            if (clean.isNotBlank() && !selectedAllergies.contains(clean)) {
                                selectedAllergies = selectedAllergies + clean
                                customAllergyInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_custom_allergy_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add allergy", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = mobilityOrFallHistory,
                    onValueChange = { mobilityOrFallHistory = it },
                    label = { Text(stringResource(R.string.med_id_mobility_history)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("medical_id_mobility_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = emergencyNotes,
                    onValueChange = { emergencyNotes = it },
                    label = { Text(stringResource(R.string.med_id_notes)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("medical_id_notes_input")
                )
            }

            // 5. Privacy & Consent Switches
            PastelCard(containerColor = LavenderSoftSurface) {
                SectionEyebrow(text = "PRIVACY & CONSENT CONTROLS")
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow AI Health Cross-Check",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Cross-references post-fall symptoms against your Medical ID to flag medication or condition risks.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = allowAiCrossCheck,
                        onCheckedChange = { allowAiCrossCheck = it },
                        modifier = Modifier.testTag("medical_id_allow_ai_switch")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Include Medical ID in Emergency SOS Alerts",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Shares a concise summary of your blood type, conditions, medications, and allergies with your trusted contact during an SOS.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = includeInEmergencyAlerts,
                        onCheckedChange = { includeInEmergencyAlerts = it },
                        modifier = Modifier.testTag("medical_id_include_sos_switch")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Private Cloud Backup (Firestore)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Backs up your Medical ID to your private, owner-only Cloud Firestore account.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = syncToCloudFirestore,
                        onCheckedChange = { syncToCloudFirestore = it },
                        modifier = Modifier.testTag("medical_id_cloud_sync_switch")
                    )
                }
            }

            // Save & Clear Buttons
            Button(
                onClick = {
                    val updated = MedicalIdProfile(
                        profileId = currentProfile.profileId,
                        fullName = fullName.trim(),
                        dateOfBirth = dateOfBirth.trim(),
                        ageOrGroup = ageOrGroup.trim(),
                        bloodType = bloodType.trim(),
                        knownConditions = selectedConditions,
                        medications = selectedMedications,
                        allergies = selectedAllergies,
                        mobilityOrFallHistory = mobilityOrFallHistory.trim(),
                        emergencyNotes = emergencyNotes.trim(),
                        allowAiCrossCheck = allowAiCrossCheck,
                        includeInEmergencyAlerts = includeInEmergencyAlerts,
                        syncToCloudFirestore = syncToCloudFirestore,
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                    onSaveProfile(updated)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintConfirm,
                    contentColor = DeepCharcoal
                ),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp)
                    .testTag("save_medical_id_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DeepCharcoal,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.med_id_save),
                    color = DeepCharcoal,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (currentProfile.isConfigured) {
                OutlinedButton(
                    onClick = {
                        onClearProfile()
                        onClose()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralUrgentHighlight),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("clear_medical_id_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.medical_id_clear))
                }
            }
        }
    }
}

@Composable
private fun MedicalTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        color = if (selected) MintSoftSurface else WarmMistBackground,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) DeepCharcoal else HairlineDivider,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DeepCharcoal,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = DeepCharcoal
            )
        }
    }
}
