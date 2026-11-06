package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import com.example.emergency.EmergencyCallConfirmationDialog
import com.example.emergency.EmergencyCallManager
import com.example.emergency.EmergencyServiceType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.DeliveryConfigStatus
import com.example.domain.FeelingOkayStatus
import com.example.domain.HealthAdvisoryTier
import com.example.domain.HealthCrossCheckResult
import com.example.domain.IncidentVerificationType
import com.example.domain.MedicalIdProfile
import com.example.domain.PainLevel
import com.example.domain.PostFallAssessmentDraft
import com.example.domain.TriStateAnswer
import com.example.domain.TrustedContact
import com.example.ui.components.PastelCard
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderHighlight
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.SkyInfoSurface
import com.example.ui.theme.WarmMistBackground
import java.util.Locale

// Accessible high-contrast semantic indicator colors paired with distinct icons/shapes
private val ElderlySafeGreen = Color(0xFF2E7D32)
private val ElderlySafeGreenBg = Color(0xFFE6F4EA)
private val ElderlyCautionYellow = Color(0xFFF9A825)
private val ElderlyCautionYellowBg = Color(0xFFFFF8E1)
private val ElderlyModerateOrange = Color(0xFFEF6C00)
private val ElderlyModerateOrangeBg = Color(0xFFFFF3E0)
private val ElderlyUrgentRed = Color(0xFFC62828)
private val ElderlyUrgentRedBg = Color(0xFFFDECEA)
private val ElderlyNeutralGrey = Color(0xFF546E7A)
private val ElderlyNeutralGreyBg = Color(0xFFECEFF1)

private const val TOTAL_ASSESSMENT_STEPS = 5

/**
 * Elderly-friendly, one-question-per-screen Post-Fall Assessment & AI Health Cross-Check UI.
 *
 * Appears immediately after the user taps "I'm OK" on the 15-second fall check-in screen
 * (or when launched manually from the Home screen).
 *
 * Key Accessibility & Safety Guarantees:
 * - Presents 1 question per screen ("1 of 5" to "5 of 5") with large 22–24sp question text
 *   and 18–20sp answer labels inside 68dp+ full-width touch buttons.
 * - Combines distinct icons, badge shapes, and text labels so meaning never relies on color alone.
 * - Includes optional Voice Readout (Text-to-Speech) to read each question and its answers aloud.
 * - False-detection selections ("Only my phone fell", "I was just moving", "Wrong detection")
 *   are never classified as human falls and can be closed immediately in one tap.
 * - Severe symptoms ("Severe" pain on Q3 or "No" to breathing normally on Q4) immediately stop
 *   the questionnaire and show urgent emergency guidance.
 */
@Composable
fun PostFallAssessmentScreen(
    draft: PostFallAssessmentDraft?,
    result: HealthCrossCheckResult?,
    isEvaluating: Boolean,
    medicalIdProfile: MedicalIdProfile,
    trustedContact: TrustedContact,
    deliveryStatus: DeliveryConfigStatus,
    deliveryStatusDetail: String,
    onUpdateDraft: (PostFallAssessmentDraft) -> Unit,
    onRecordFalseDetectionAndClose: (IncidentVerificationType, String) -> Unit,
    onSubmitAssessment: () -> Unit,
    onSkipAssessment: () -> Unit,
    onDismissResult: () -> Unit,
    onRequestImmediateSos: (String) -> Unit,
    onOpenMedicalIdEditor: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var currentStepIndex by rememberSaveable(draft?.assessmentId ?: "default") {
        mutableIntStateOf(0)
    }
    var continueHealthCheckAfterFalseAlarm by rememberSaveable(draft?.assessmentId ?: "default") {
        mutableStateOf(false)
    }
    var voiceReadoutEnabled by rememberSaveable {
        mutableStateOf(false)
    }

    // Optional Android TextToSpeech for reading questions and answers aloud
    var ttsReady by remember { mutableStateOf(false) }
    val ttsHolder = remember(context) {
        var instance: TextToSpeech? = null
        try {
            instance = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        instance?.language = Locale.US
                        instance?.setSpeechRate(0.92f)
                        ttsReady = true
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        instance
    }

    DisposableEffect(ttsHolder) {
        onDispose {
            try {
                ttsHolder?.stop()
                ttsHolder?.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    fun speakTextAloud(textToSpeak: String) {
        if (!ttsReady) return
        try {
            ttsHolder?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "solosignal_pfa_tts")
        } catch (_: Exception) {
        }
    }

    BackHandler {
        when {
            result != null -> onDismissResult()
            currentStepIndex > 0 -> currentStepIndex -= 1
            else -> onSkipAssessment()
        }
    }

    var showEmergencyCallDialog by remember { mutableStateOf(false) }
    var requestedEmergencyServiceType by remember { mutableStateOf(EmergencyServiceType.AMBULANCE) }

    fun openEmergencyServicesDialog(serviceType: EmergencyServiceType = EmergencyServiceType.AMBULANCE) {
        requestedEmergencyServiceType = serviceType
        showEmergencyCallDialog = true
    }

    // Automatically read the current question or result when Voice Readout is toggled ON
    LaunchedEffect(voiceReadoutEnabled, currentStepIndex, result?.assessmentId) {
        if (voiceReadoutEnabled && ttsReady) {
            val speech = if (result != null) {
                "${result.advisoryHeadline}. ${result.advisoryExplanation}"
            } else {
                buildStepSpeechText(currentStepIndex)
            }
            speakTextAloud(speech)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .testTag("post_fall_assessment_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // =========================================================================
            // RESULT SCREEN: Shown after completing the assessment or immediately on urgent symptom
            // =========================================================================
            if (result != null) {
                PostFallSafetyAdvisoryResultView(
                    result = result,
                    isEvaluating = isEvaluating,
                    medicalIdProfile = medicalIdProfile,
                    trustedContact = trustedContact,
                    deliveryStatus = deliveryStatus,
                    deliveryStatusDetail = deliveryStatusDetail,
                    voiceReadoutEnabled = voiceReadoutEnabled,
                    onToggleVoiceReadout = {
                        val next = !voiceReadoutEnabled
                        voiceReadoutEnabled = next
                        if (next) {
                            speakTextAloud("${result.advisoryHeadline}. ${result.advisoryExplanation}")
                        } else {
                            try {
                                ttsHolder?.stop()
                            } catch (_: Exception) {
                            }
                        }
                    },
                    onDialEmergencyServices = { openEmergencyServicesDialog(EmergencyServiceType.AMBULANCE) },
                    onSendSosNow = {
                        onRequestImmediateSos(
                            "Post-fall check help requested (${result.riskTier.badgeLabel}: ${result.advisoryHeadline})"
                        )
                    },
                    onOpenMedicalIdEditor = onOpenMedicalIdEditor,
                    onDone = onDismissResult
                )
                return@Column
            }

            val currentDraft = draft ?: return@Column

            // =========================================================================
            // TOP BAR: Progress ("1 of 5") + Voice Readout Toggle + Skip Button
            // =========================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MintSoftSurface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .border(1.5.dp, DeepCharcoal, RoundedCornerShape(999.dp))
                        .testTag("post_fall_progress_badge")
                ) {
                    Text(
                        text = "${currentStepIndex + 1} of $TOTAL_ASSESSMENT_STEPS",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = DeepCharcoal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val next = !voiceReadoutEnabled
                            voiceReadoutEnabled = next
                            if (next) {
                                speakTextAloud(buildStepSpeechText(currentStepIndex))
                            } else {
                                try {
                                    ttsHolder?.stop()
                                } catch (_: Exception) {
                                }
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("toggle_voice_readout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Read question aloud",
                            tint = DeepCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (voiceReadoutEnabled) "Voice On" else "Read Aloud",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    }

                    OutlinedButton(
                        onClick = onSkipAssessment,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("skip_post_fall_assessment_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Skip check-in",
                            tint = DeepCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Skip",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    }
                }
            }

            // Accessible step progress bar
            LinearProgressIndicator(
                progress = { (currentStepIndex + 1).toFloat() / TOTAL_ASSESSMENT_STEPS.toFloat() },
                color = DeepCharcoal,
                trackColor = HairlineDivider,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .testTag("post_fall_step_progress_bar")
            )

            // =========================================================================
            // SINGLE-QUESTION CARD (1 Question Per Screen)
            // =========================================================================
            PastelCard(
                containerColor = CardSurfaceWhite,
                borderColor = DeepCharcoal,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("post_fall_question_step_${currentStepIndex + 1}_card")
            ) {
                when (currentStepIndex) {
                    // -----------------------------------------------------------------
                    // QUESTION 1: What happened?
                    // -----------------------------------------------------------------
                    0 -> {
                        QuestionStepHeader(
                            stepNumber = 1,
                            questionTitle = stringResource(R.string.pfa_q1_title)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val selectedVerification = currentDraft.incidentVerification
                        val showFalseAlarmConfirmation = selectedVerification != null &&
                            selectedVerification.isFalseDetection &&
                            !continueHealthCheckAfterFalseAlarm

                        if (showFalseAlarmConfirmation) {
                            FalseDetectionImmediateCloseCard(
                                verification = selectedVerification!!,
                                onConfirmCloseFalseDetection = {
                                    onRecordFalseDetectionAndClose(selectedVerification, "")
                                },
                                onContinueHealthCheckAnyway = {
                                    continueHealthCheckAfterFalseAlarm = true
                                    currentStepIndex = 1
                                },
                                onChangeAnswer = {
                                    onUpdateDraft(currentDraft.copy(incidentVerification = null))
                                }
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                IncidentVerificationType.personalLearningWizardOptions.forEach { option ->
                                    val isSelected = currentDraft.incidentVerification == option
                                    val (icon, badgeColor, badgeBg, symbolTag, optLabel) = when (option) {
                                        IncidentVerificationType.EXPERIENCED_FALL -> Quintuple(
                                            Icons.Default.AccessibilityNew,
                                            ElderlyUrgentRed,
                                            ElderlyUrgentRedBg,
                                            stringResource(R.string.pfa_badge_fall),
                                            stringResource(R.string.pfa_opt_fell)
                                        )
                                        IncidentVerificationType.PHONE_FELL_NOT_ME,
                                        IncidentVerificationType.PHONE_DROPPED_OR_PLACED -> Quintuple(
                                            Icons.Default.Smartphone,
                                            ElderlySafeGreen,
                                            ElderlySafeGreenBg,
                                            stringResource(R.string.pfa_badge_phone_only),
                                            stringResource(R.string.pfa_opt_phone_fell)
                                        )
                                        IncidentVerificationType.SITTING_DOWN -> Quintuple(
                                            Icons.Default.AccessibilityNew,
                                            ElderlySafeGreen,
                                            ElderlySafeGreenBg,
                                            stringResource(R.string.pfa_badge_sitting),
                                            stringResource(R.string.pfa_opt_sitting)
                                        )
                                        IncidentVerificationType.NORMAL_MOVEMENT -> Quintuple(
                                            Icons.AutoMirrored.Filled.DirectionsWalk,
                                            ElderlySafeGreen,
                                            ElderlySafeGreenBg,
                                            stringResource(R.string.pfa_badge_normal),
                                            stringResource(R.string.pfa_opt_moving)
                                        )
                                        IncidentVerificationType.FALSE_ALARM -> Quintuple(
                                            Icons.Default.Cancel,
                                            ElderlyCautionYellow,
                                            ElderlyCautionYellowBg,
                                            stringResource(R.string.pfa_badge_false_alarm),
                                            stringResource(R.string.pfa_opt_false_alarm)
                                        )
                                        IncidentVerificationType.NOT_SURE -> Quintuple(
                                            Icons.AutoMirrored.Filled.HelpOutline,
                                            ElderlyNeutralGrey,
                                            ElderlyNeutralGreyBg,
                                            stringResource(R.string.pfa_badge_unsure),
                                            stringResource(R.string.pfa_opt_unsure)
                                        )
                                    }

                                    ElderlyIllustratedAnswerButton(
                                        label = optLabel,
                                        icon = icon,
                                        iconTint = badgeColor,
                                        iconContainerColor = badgeBg,
                                        supportingBadgeText = symbolTag,
                                        selected = isSelected,
                                        isUrgent = false,
                                        testTag = "incident_verification_option_${option.name.lowercase()}",
                                        onClick = {
                                            val updated = currentDraft.copy(incidentVerification = option)
                                            onUpdateDraft(updated)
                                            if (!option.isFalseDetection) {
                                                currentStepIndex = 1
                                            } else {
                                                continueHealthCheckAfterFalseAlarm = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // QUESTION 2: Are you feeling okay?
                    // -----------------------------------------------------------------
                    1 -> {
                        QuestionStepHeader(
                            stepNumber = 2,
                            questionTitle = stringResource(R.string.pfa_q2_title)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Option 1: Green smiling face — Yes
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_feeling_yes),
                                icon = Icons.Default.SentimentVerySatisfied,
                                iconTint = ElderlySafeGreen,
                                iconContainerColor = ElderlySafeGreenBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_good),
                                selected = currentDraft.feelingOkay == FeelingOkayStatus.YES,
                                isUrgent = false,
                                testTag = "q2_feeling_okay_yes",
                                onClick = {
                                    onUpdateDraft(currentDraft.withFeelingOkay(FeelingOkayStatus.YES))
                                    currentStepIndex = 2
                                }
                            )

                            // Option 2: Yellow neutral face — A little uncomfortable
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_feeling_uncomfortable),
                                icon = Icons.Default.SentimentNeutral,
                                iconTint = ElderlyCautionYellow,
                                iconContainerColor = ElderlyCautionYellowBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_caution),
                                selected = currentDraft.feelingOkay == FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE,
                                isUrgent = false,
                                testTag = "q2_feeling_okay_a_little_uncomfortable",
                                onClick = {
                                    onUpdateDraft(currentDraft.withFeelingOkay(FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE))
                                    currentStepIndex = 2
                                }
                            )

                            // Option 3: Red concerned face — No
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_feeling_no),
                                icon = Icons.Default.SentimentVeryDissatisfied,
                                iconTint = ElderlyUrgentRed,
                                iconContainerColor = ElderlyUrgentRedBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_unwell),
                                selected = currentDraft.feelingOkay == FeelingOkayStatus.NO,
                                isUrgent = true,
                                testTag = "q2_feeling_okay_no",
                                onClick = {
                                    onUpdateDraft(currentDraft.withFeelingOkay(FeelingOkayStatus.NO))
                                    currentStepIndex = 2
                                }
                            )
                        }
                    }

                    // -----------------------------------------------------------------
                    // QUESTION 3: Are you in pain?
                    // (Severe pain immediately shows emergency guidance!)
                    // -----------------------------------------------------------------
                    2 -> {
                        QuestionStepHeader(
                            stepNumber = 3,
                            questionTitle = stringResource(R.string.pfa_q3_title)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Green circle — No
                            ElderlyPainCircleAnswerButton(
                                label = stringResource(R.string.pfa_pain_none),
                                circleColor = ElderlySafeGreen,
                                circleBgColor = ElderlySafeGreenBg,
                                innerIcon = Icons.Default.Check,
                                levelBadge = stringResource(R.string.pfa_badge_none),
                                selected = currentDraft.painLevel == PainLevel.NO_PAIN,
                                isUrgent = false,
                                testTag = "pain_level_option_no_pain",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(painLevel = PainLevel.NO_PAIN))
                                    currentStepIndex = 3
                                }
                            )

                            // Yellow circle — A little
                            ElderlyPainCircleAnswerButton(
                                label = stringResource(R.string.pfa_pain_mild),
                                circleColor = ElderlyCautionYellow,
                                circleBgColor = ElderlyCautionYellowBg,
                                innerIcon = Icons.Default.Remove,
                                levelBadge = stringResource(R.string.pfa_badge_mild),
                                selected = currentDraft.painLevel == PainLevel.MILD_PAIN,
                                isUrgent = false,
                                testTag = "pain_level_option_mild_pain",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(painLevel = PainLevel.MILD_PAIN))
                                    currentStepIndex = 3
                                }
                            )

                            // Orange circle — Moderate
                            ElderlyPainCircleAnswerButton(
                                label = stringResource(R.string.pfa_pain_moderate),
                                circleColor = ElderlyModerateOrange,
                                circleBgColor = ElderlyModerateOrangeBg,
                                innerIcon = Icons.Default.PriorityHigh,
                                levelBadge = stringResource(R.string.pfa_badge_moderate),
                                selected = currentDraft.painLevel == PainLevel.MODERATE_PAIN,
                                isUrgent = false,
                                testTag = "pain_level_option_moderate_pain",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(painLevel = PainLevel.MODERATE_PAIN))
                                    currentStepIndex = 3
                                }
                            )

                            // Red circle — Severe (IMMEDIATELY stops questionnaire & shows emergency guidance)
                            ElderlyPainCircleAnswerButton(
                                label = stringResource(R.string.pfa_pain_severe),
                                circleColor = ElderlyUrgentRed,
                                circleBgColor = ElderlyUrgentRedBg,
                                innerIcon = Icons.Default.Warning,
                                levelBadge = stringResource(R.string.pfa_badge_urgent),
                                selected = currentDraft.painLevel == PainLevel.SEVERE_PAIN,
                                isUrgent = true,
                                testTag = "pain_level_option_severe_pain",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(painLevel = PainLevel.SEVERE_PAIN))
                                    // Do not make the user finish remaining questions when severe pain is reported
                                    onSubmitAssessment()
                                }
                            )
                        }
                    }

                    // -----------------------------------------------------------------
                    // QUESTION 4: Are you breathing normally?
                    // ("No" immediately shows emergency guidance!)
                    // -----------------------------------------------------------------
                    3 -> {
                        QuestionStepHeader(
                            stepNumber = 4,
                            questionTitle = stringResource(R.string.pfa_q4_title)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val selectedBreathingNormal = currentDraft.breathingNormally

                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Green icon — Yes (maps to difficultyBreathing = NO)
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_yes),
                                icon = Icons.Default.CheckCircle,
                                iconTint = ElderlySafeGreen,
                                iconContainerColor = ElderlySafeGreenBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_normal),
                                selected = selectedBreathingNormal == TriStateAnswer.YES,
                                isUrgent = false,
                                testTag = "q4_breathing_normally_yes",
                                onClick = {
                                    onUpdateDraft(currentDraft.withBreathingNormally(TriStateAnswer.YES))
                                    currentStepIndex = 4
                                }
                            )

                            // Red icon — No (maps to difficultyBreathing = YES -> IMMEDIATE emergency guidance)
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_no),
                                icon = Icons.Default.Warning,
                                iconTint = ElderlyUrgentRed,
                                iconContainerColor = ElderlyUrgentRedBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_urgent),
                                selected = selectedBreathingNormal == TriStateAnswer.NO,
                                isUrgent = true,
                                testTag = "q4_breathing_normally_no",
                                onClick = {
                                    onUpdateDraft(currentDraft.withBreathingNormally(TriStateAnswer.NO))
                                    // Immediately show emergency guidance without forcing Question 5
                                    onSubmitAssessment()
                                }
                            )

                            // Grey question mark — Not sure (maps to difficultyBreathing = UNSURE)
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_not_sure),
                                icon = Icons.AutoMirrored.Filled.HelpOutline,
                                iconTint = ElderlyNeutralGrey,
                                iconContainerColor = ElderlyNeutralGreyBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_unsure),
                                selected = selectedBreathingNormal == TriStateAnswer.UNSURE,
                                isUrgent = false,
                                testTag = "q4_breathing_normally_not_sure",
                                onClick = {
                                    onUpdateDraft(currentDraft.withBreathingNormally(TriStateAnswer.UNSURE))
                                    currentStepIndex = 4
                                }
                            )
                        }
                    }

                    // -----------------------------------------------------------------
                    // QUESTION 5: Do you need help?
                    // -----------------------------------------------------------------
                    else -> {
                        QuestionStepHeader(
                            stepNumber = 5,
                            questionTitle = stringResource(R.string.pfa_q5_title)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Green hand/thumb icon — No
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_no),
                                icon = Icons.Default.ThumbUp,
                                iconTint = ElderlySafeGreen,
                                iconContainerColor = ElderlySafeGreenBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_im_okay),
                                selected = currentDraft.needMedicalAssistance == TriStateAnswer.NO,
                                isUrgent = false,
                                testTag = "q_need_help_no",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(needMedicalAssistance = TriStateAnswer.NO))
                                    onSubmitAssessment()
                                }
                            )

                            // Red help icon — Yes
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_yes),
                                icon = Icons.Default.Call,
                                iconTint = ElderlyUrgentRed,
                                iconContainerColor = ElderlyUrgentRedBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_get_help),
                                selected = currentDraft.needMedicalAssistance == TriStateAnswer.YES,
                                isUrgent = true,
                                testTag = "q_need_help_yes",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(needMedicalAssistance = TriStateAnswer.YES))
                                    onSubmitAssessment()
                                }
                            )

                            // Grey question mark — Not sure
                            ElderlyIllustratedAnswerButton(
                                label = stringResource(R.string.pfa_ans_not_sure),
                                icon = Icons.AutoMirrored.Filled.HelpOutline,
                                iconTint = ElderlyNeutralGrey,
                                iconContainerColor = ElderlyNeutralGreyBg,
                                supportingBadgeText = stringResource(R.string.pfa_badge_unsure),
                                selected = currentDraft.needMedicalAssistance == TriStateAnswer.UNSURE,
                                isUrgent = false,
                                testTag = "q_need_help_unsure",
                                onClick = {
                                    onUpdateDraft(currentDraft.copy(needMedicalAssistance = TriStateAnswer.UNSURE))
                                    onSubmitAssessment()
                                }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // DEDICATED BOTTOM NAVIGATION CONTAINER
            // Both buttons fit completely inside, balanced heights, responsive spacing
            // =========================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardSurfaceWhite,
                border = BorderStroke(1.5.dp, HairlineDivider),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("assessment_bottom_navigation_container")
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    val availableWidth = maxWidth
                    val isNarrow = availableWidth < 360.dp

                    if (isNarrow && currentStepIndex > 0) {
                        // Narrow screens: 2-tier layout so buttons never overlap or clip
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous: secondary outlined button
                                OutlinedButton(
                                    onClick = { currentStepIndex -= 1 },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.5.dp, DeepCharcoal),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("assessment_previous_step_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Previous question",
                                        tint = DeepCharcoal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.pfa_btn_previous),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Skip: simple secondary outlined button
                                OutlinedButton(
                                    onClick = {
                                        if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                            currentStepIndex += 1
                                        } else {
                                            onSubmitAssessment()
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.5.dp, NeutralSlate.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("assessment_skip_question_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.pfa_btn_skip),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeutralSlate,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Next / Finish: primary filled button
                            Button(
                                onClick = {
                                    if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                        currentStepIndex += 1
                                    } else {
                                        onSubmitAssessment()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepCharcoal,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag(
                                        if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                            "assessment_next_step_button"
                                        } else {
                                            "submit_post_fall_assessment_button"
                                        }
                                    )
                            ) {
                                Text(
                                    text = if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) stringResource(R.string.pfa_btn_next) else stringResource(R.string.pfa_btn_finish),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                        Icons.AutoMirrored.Filled.ArrowForward
                                    } else {
                                        Icons.Default.CheckCircle
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        // Standard & wide screens: balanced single row with equal heights
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentStepIndex > 0) {
                                // Previous: secondary outlined button
                                OutlinedButton(
                                    onClick = { currentStepIndex -= 1 },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.5.dp, DeepCharcoal),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("assessment_previous_step_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Previous question",
                                        tint = DeepCharcoal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.pfa_btn_previous),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Skip: simple secondary outlined / text button
                            OutlinedButton(
                                onClick = {
                                    if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                        currentStepIndex += 1
                                    } else {
                                        onSubmitAssessment()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.5.dp, NeutralSlate.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(0.9f)
                                    .height(52.dp)
                                    .testTag("assessment_skip_question_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.pfa_btn_skip),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeutralSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Next / Finish: primary filled button
                            Button(
                                onClick = {
                                    if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                        currentStepIndex += 1
                                    } else {
                                        onSubmitAssessment()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepCharcoal,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(52.dp)
                                    .testTag(
                                        if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                            "assessment_next_step_button"
                                        } else {
                                            "submit_post_fall_assessment_button"
                                        }
                                    )
                            ) {
                                Text(
                                    text = if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) stringResource(R.string.pfa_btn_next) else stringResource(R.string.pfa_btn_finish),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (currentStepIndex < TOTAL_ASSESSMENT_STEPS - 1) {
                                        Icons.AutoMirrored.Filled.ArrowForward
                                    } else {
                                        Icons.Default.CheckCircle
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // ALWAYS-VISIBLE EMERGENCY HELP BAR (So elderly users never have to wait)
            // =========================================================================
            Button(
                onClick = { openEmergencyServicesDialog(EmergencyServiceType.AMBULANCE) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CoralSoftSurface,
                    contentColor = DeepCharcoal
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .border(2.dp, CoralUrgentHighlight, RoundedCornerShape(16.dp))
                    .testTag("severe_banner_call_emergency_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = DeepCharcoal,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Need Help Now? Call Emergency Services",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DeepCharcoal
                )
            }
        }
    }

    if (showEmergencyCallDialog) {
        EmergencyCallConfirmationDialog(
            initialServiceType = requestedEmergencyServiceType,
            onDismiss = { showEmergencyCallDialog = false }
        )
    }
}

@Composable
private fun QuestionStepHeader(
    stepNumber: Int,
    questionTitle: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.post_fall_step_progress, stepNumber, TOTAL_ASSESSMENT_STEPS),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = NeutralSlate
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = questionTitle,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = DeepCharcoal,
            modifier = Modifier.testTag("post_fall_question_${stepNumber}_title")
        )
    }
}

/**
 * Large, elderly-friendly illustrated button (minimum 68.dp height, 20.sp label,
 * 48.dp high-contrast icon badge + shape/text tag so color is never the sole indicator).
 */
@Composable
private fun ElderlyIllustratedAnswerButton(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    iconContainerColor: Color,
    supportingBadgeText: String,
    selected: Boolean,
    isUrgent: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        selected && isUrgent -> CoralSoftSurface
        selected -> MintSoftSurface
        else -> WarmMistBackground
    }
    val borderColor = when {
        selected -> DeepCharcoal
        isUrgent -> CoralUrgentHighlight
        else -> DeepCharcoal.copy(alpha = 0.28f)
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 70.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (selected) 3.dp else 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label ($supportingBadgeText)" }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(iconContainerColor)
                        .border(2.dp, iconTint, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = label,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = DeepCharcoal,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Specialized large button for Question 3 ("Are you in pain?") featuring a colored circle
 * with an interior symbol + text label so color is never the sole indicator.
 */
@Composable
private fun ElderlyPainCircleAnswerButton(
    label: String,
    circleColor: Color,
    circleBgColor: Color,
    innerIcon: ImageVector,
    levelBadge: String,
    selected: Boolean,
    isUrgent: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        selected && isUrgent -> CoralSoftSurface
        selected -> MintSoftSurface
        else -> WarmMistBackground
    }
    val borderColor = when {
        selected -> DeepCharcoal
        isUrgent -> CoralUrgentHighlight
        else -> DeepCharcoal.copy(alpha = 0.28f)
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 70.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (selected) 3.dp else 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label pain ($levelBadge)" }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Colored circle with distinct interior symbol
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(circleBgColor)
                        .border(3.dp, circleColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(circleColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = innerIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = label,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            }

            Surface(
                color = circleBgColor,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.border(1.dp, circleColor, RoundedCornerShape(999.dp))
            ) {
                Text(
                    text = levelBadge,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Shown immediately on Question 1 when the user selects a false detection option
 * ("Only my phone fell", "I was just moving", or "Wrong detection").
 * Classifies the event as a false detection (never a human fall) and lets the user
 * close the check-in safely in one tap.
 */
@Composable
private fun FalseDetectionImmediateCloseCard(
    verification: IncidentVerificationType,
    onConfirmCloseFalseDetection: () -> Unit,
    onContinueHealthCheckAnyway: () -> Unit,
    onChangeAnswer: () -> Unit
) {
    Surface(
        color = SkyInfoSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, DeepCharcoal, RoundedCornerShape(18.dp))
            .testTag("false_detection_confirmation_box")
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElderlySafeGreenBg)
                        .border(2.dp, ElderlySafeGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (verification.isPhoneDrop) {
                            Icons.Default.Smartphone
                        } else {
                            Icons.Default.CheckCircle
                        },
                        contentDescription = null,
                        tint = ElderlySafeGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }

                val verificationLabel = when (verification) {
                    IncidentVerificationType.EXPERIENCED_FALL -> stringResource(R.string.pfa_opt_fell)
                    IncidentVerificationType.PHONE_FELL_NOT_ME,
                    IncidentVerificationType.PHONE_DROPPED_OR_PLACED -> stringResource(R.string.pfa_opt_phone_fell)
                    IncidentVerificationType.SITTING_DOWN -> stringResource(R.string.pfa_opt_sitting)
                    IncidentVerificationType.NORMAL_MOVEMENT -> stringResource(R.string.pfa_opt_moving)
                    IncidentVerificationType.FALSE_ALARM -> stringResource(R.string.pfa_opt_false_alarm)
                    IncidentVerificationType.NOT_SURE -> stringResource(R.string.pfa_opt_unsure)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = verificationLabel,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = DeepCharcoal
                    )
                    Text(
                        text = if (verification.isPhoneDrop) {
                            "Glad you are okay! We will save this as a phone drop, not a fall."
                        } else {
                            "Glad you are okay! No emergency alert will be sent."
                        },
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        color = DeepCharcoal
                    )
                }
            }

            Button(
                onClick = onConfirmCloseFalseDetection,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintConfirm,
                    contentColor = DeepCharcoal
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .border(2.dp, DeepCharcoal, RoundedCornerShape(16.dp))
                    .testTag("confirm_false_detection_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.pfa_btn_done_safe),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            OutlinedButton(
                onClick = onContinueHealthCheckAnyway,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .testTag("continue_health_check_anyway_button")
            ) {
                Text(
                    text = stringResource(R.string.pfa_btn_check_health_anyway),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            }

            OutlinedButton(
                onClick = onChangeAnswer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("change_q1_answer_button")
            ) {
                Text(
                    text = stringResource(R.string.pfa_btn_change_answer),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DeepCharcoal
                )
            }
        }
    }
}

/**
 * Elderly-friendly Post-Fall Safety Guidance & Medical ID Cross-Check Result View.
 *
 * Uses large typography (22–24sp headline, 18–20sp short bullet points), simple English,
 * prominent 64dp+ emergency assistance buttons when urgent or help is requested, and
 * clear Medical ID cross-check highlights.
 */
@Composable
private fun PostFallSafetyAdvisoryResultView(
    result: HealthCrossCheckResult,
    isEvaluating: Boolean,
    medicalIdProfile: MedicalIdProfile,
    trustedContact: TrustedContact,
    deliveryStatus: DeliveryConfigStatus,
    deliveryStatusDetail: String,
    voiceReadoutEnabled: Boolean,
    onToggleVoiceReadout: () -> Unit,
    onDialEmergencyServices: () -> Unit,
    onSendSosNow: () -> Unit,
    onOpenMedicalIdEditor: () -> Unit,
    onDone: () -> Unit
) {
    val isUrgent = result.riskTier == HealthAdvisoryTier.URGENT_WARNING
    val (cardColor, borderColor, pillColor) = when (result.riskTier) {
        HealthAdvisoryTier.URGENT_WARNING -> Triple(CoralSoftSurface, CoralUrgentHighlight, CoralUrgentHighlight)
        HealthAdvisoryTier.CAUTION_ADVISORY -> Triple(LavenderSoftSurface, LavenderHighlight, LavenderHighlight)
        HealthAdvisoryTier.SELF_MONITORING -> Triple(MintSoftSurface, MintConfirm, MintConfirm)
    }

    val tierSimpleBadge = when (result.riskTier) {
        HealthAdvisoryTier.URGENT_WARNING -> stringResource(R.string.pfa_tier_urgent)
        HealthAdvisoryTier.CAUTION_ADVISORY -> stringResource(R.string.pfa_tier_caution)
        HealthAdvisoryTier.SELF_MONITORING -> stringResource(R.string.pfa_tier_low_risk)
    }

    val context = LocalContext.current
    val resolvedEmergency = remember {
        EmergencyCallManager.resolveEmergencyNumber(context, EmergencyServiceType.AMBULANCE)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("post_fall_advisory_result_view"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPillBadge(
                label = stringResource(R.string.pfa_result_title),
                containerColor = SkyInfoSurface
            )

            OutlinedButton(
                onClick = onToggleVoiceReadout,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("result_voice_readout_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Read result aloud",
                    tint = DeepCharcoal,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (voiceReadoutEnabled) stringResource(R.string.post_fall_voice_on) else stringResource(R.string.post_fall_read_aloud),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            }
        }

        if (isEvaluating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = DeepCharcoal
                )
                Text(
                    text = stringResource(R.string.pfa_evaluating),
                    fontSize = 16.sp,
                    color = NeutralSlate
                )
            }
        }

        // If urgent or help was requested, place the Emergency Assistance buttons right at the top
        // so the user does not need to scroll to get help.
        if (isUrgent) {
            Button(
                onClick = onDialEmergencyServices,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElderlyUrgentRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 68.dp)
                    .testTag("advisory_call_emergency_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.pfa_btn_call_emergency, if (resolvedEmergency.isDetermined) resolvedEmergency.number else "Ambulance"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (trustedContact.isValid && !result.autoSosTriggered) {
                Button(
                    onClick = onSendSosNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 64.dp)
                        .testTag("advisory_send_sos_contact_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pfa_btn_alert_contact),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        PastelCard(
            containerColor = cardColor,
            borderColor = borderColor,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("post_fall_safety_advisory_card")
        ) {
            StatusPillBadge(
                label = tierSimpleBadge,
                containerColor = pillColor,
                modifier = Modifier.testTag("advisory_risk_tier_badge")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = result.advisoryHeadline,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DeepCharcoal,
                modifier = Modifier.testTag("advisory_headline_text")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = result.advisoryExplanation,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                color = DeepCharcoal,
                modifier = Modifier.testTag("advisory_explanation_text")
            )

            if (result.autoSosTriggered) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = CardSurfaceWhite,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, DeepCharcoal, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Emergency Alert Sent (${deliveryStatus.label})",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DeepCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = deliveryStatusDetail,
                            fontSize = 16.sp,
                            color = NeutralSlate
                        )
                    }
                }
            }

            if (result.crossCheckHighlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (result.usedMedicalIdContext) {
                        "What We Noticed (With Your Medical ID):"
                    } else {
                        "What We Noticed:"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DeepCharcoal
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.crossCheckHighlights.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "• ",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = DeepCharcoal
                            )
                            Text(
                                text = item,
                                fontSize = 18.sp,
                                lineHeight = 25.sp,
                                color = DeepCharcoal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (result.recommendedNextSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "What To Do Next:",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DeepCharcoal
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.recommendedNextSteps.forEachIndexed { idx, step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${idx + 1}. ",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = DeepCharcoal
                            )
                            Text(
                                text = step,
                                fontSize = 18.sp,
                                lineHeight = 25.sp,
                                color = DeepCharcoal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // If not already shown at the top, keep Emergency Services & Trusted Contact buttons accessible below
        if (!isUrgent) {
            Button(
                onClick = onDialEmergencyServices,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CoralUrgentHighlight,
                    contentColor = DeepCharcoal
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 62.dp)
                    .testTag("advisory_call_emergency_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.pfa_btn_call_emergency, if (resolvedEmergency.isDetermined) resolvedEmergency.number else "Ambulance"),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (trustedContact.isValid && !result.autoSosTriggered) {
                OutlinedButton(
                    onClick = onSendSosNow,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 60.dp)
                        .testTag("advisory_send_sos_contact_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = DeepCharcoal,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pfa_btn_alert_contact),
                        fontSize = 18.sp,
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (!medicalIdProfile.isConfigured) {
            OutlinedButton(
                onClick = onOpenMedicalIdEditor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .testTag("advisory_open_medical_id_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MedicalInformation,
                    contentDescription = null,
                    tint = DeepCharcoal,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Medical ID for Future Checks",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            }
        }

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = MintConfirm,
                contentColor = DeepCharcoal
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .border(2.dp, DeepCharcoal, RoundedCornerShape(18.dp))
                .testTag("advisory_done_return_home_button")
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = DeepCharcoal,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.pfa_btn_done),
                fontSize = 20.sp,
                color = DeepCharcoal,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // Non-Diagnostic Safety Note
        Surface(
            color = CardSurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("advisory_medical_disclaimer_card")
        ) {
            Text(
                text = result.nonDiagnosticDisclaimer,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = NeutralSlate,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

private fun buildStepSpeechText(stepIndex: Int): String {
    return when (stepIndex) {
        0 -> "Question 1 of 5. What happened? Options: I fell. Only my phone fell. I was just moving. Wrong detection. Or Not sure."
        1 -> "Question 2 of 5. Are you feeling okay? Options: Yes. A little uncomfortable. Or No."
        2 -> "Question 3 of 5. Are you in pain? Options: No. A little. Moderate. Or Severe."
        3 -> "Question 4 of 5. Are you breathing normally? Options: Yes. No. Or Not sure."
        else -> "Question 5 of 5. Do you need help? Options: No. Yes. Or Not sure."
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
