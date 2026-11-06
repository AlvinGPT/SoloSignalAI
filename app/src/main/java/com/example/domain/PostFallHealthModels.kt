package com.example.domain

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Step A — Incident verification options asked immediately after the user taps "I'm OK"
 * on the 15-second fall check-in screen ("What actually happened?").
 *
 * Phone drops and normal movements are explicitly flagged with [isFalseDetection] = true
 * and [isPhoneDrop] so they are never classified as a human fall and are stored for future
 * ML false-positive evaluation.
 */
enum class IncidentVerificationType(
    val displayLabel: String,
    val simpleLabel: String,
    val subtitle: String,
    val isFalseDetection: Boolean,
    val isPhoneDrop: Boolean
) {
    EXPERIENCED_FALL(
        displayLabel = "I experienced a fall.",
        simpleLabel = "I fell",
        subtitle = "I slipped, tripped, or fell.",
        isFalseDetection = false,
        isPhoneDrop = false
    ),
    PHONE_FELL_NOT_ME(
        displayLabel = "My phone fell, but I did not fall.",
        simpleLabel = "Only my phone fell",
        subtitle = "My phone dropped, not me.",
        isFalseDetection = true,
        isPhoneDrop = true
    ),
    PHONE_DROPPED_OR_PLACED(
        displayLabel = "My phone was dropped or placed down suddenly.",
        simpleLabel = "Only my phone fell",
        subtitle = "My phone was placed down hard.",
        isFalseDetection = true,
        isPhoneDrop = true
    ),
    SITTING_DOWN(
        displayLabel = "I was sitting down.",
        simpleLabel = "I was sitting down",
        subtitle = "I sat down on a chair, sofa, or bed.",
        isFalseDetection = true,
        isPhoneDrop = false
    ),
    NORMAL_MOVEMENT(
        displayLabel = "This was normal movement.",
        simpleLabel = "I was just moving",
        subtitle = "I was walking or moving normally.",
        isFalseDetection = true,
        isPhoneDrop = false
    ),
    FALSE_ALARM(
        displayLabel = "Incorrect detection / false alarm.",
        simpleLabel = "Wrong detection",
        subtitle = "Nothing happened — false alarm.",
        isFalseDetection = true,
        isPhoneDrop = false
    ),
    NOT_SURE(
        displayLabel = "I'm not sure.",
        simpleLabel = "Not sure",
        subtitle = "Let's do a quick check to make sure you are okay.",
        isFalseDetection = false,
        isPhoneDrop = false
    );

    companion object {
        /**
         * The 5 simplified options presented on Question 1 ("What happened?").
         */
        val simplifiedWizardOptions: List<IncidentVerificationType> = listOf(
            EXPERIENCED_FALL,
            PHONE_FELL_NOT_ME,
            NORMAL_MOVEMENT,
            FALSE_ALARM,
            NOT_SURE
        )

        /**
         * Extended 6-option list including "I was sitting down" for personalised movement learning.
         */
        val personalLearningWizardOptions: List<IncidentVerificationType> = listOf(
            EXPERIENCED_FALL,
            PHONE_FELL_NOT_ME,
            SITTING_DOWN,
            NORMAL_MOVEMENT,
            FALSE_ALARM,
            NOT_SURE
        )

        fun fromStorageKey(raw: String?): IncidentVerificationType? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Question 2: "Are you feeling okay?"
 */
enum class FeelingOkayStatus(
    val simpleLabel: String,
    val displayLabel: String
) {
    YES("Yes", "Yes — Feeling okay"),
    A_LITTLE_UNCOMFORTABLE("A little uncomfortable", "A little uncomfortable"),
    NO("No", "No — Not feeling okay"),
    SKIPPED("Skipped", "Skipped");

    companion object {
        fun fromStorageKey(raw: String?): FeelingOkayStatus {
            if (raw.isNullOrBlank()) return SKIPPED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SKIPPED
        }
    }
}

/**
 * Question 3: "Are you in pain?"
 */
enum class PainLevel(
    val displayLabel: String,
    val simpleLabel: String,
    val isSevere: Boolean
) {
    NO_PAIN("No pain", "No", false),
    MILD_PAIN("Mild pain", "A little", false),
    MODERATE_PAIN("Moderate pain", "Moderate", false),
    SEVERE_PAIN("Severe pain", "Severe", true),
    SKIPPED("Skipped", "Skipped", false);

    companion object {
        val wizardOptions: List<PainLevel> = listOf(
            NO_PAIN,
            MILD_PAIN,
            MODERATE_PAIN,
            SEVERE_PAIN
        )

        fun fromStorageKey(raw: String?): PainLevel {
            if (raw.isNullOrBlank()) return SKIPPED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SKIPPED
        }
    }
}

/**
 * Standard Yes / No / Not sure / Skipped answer for physical condition questions.
 */
enum class TriStateAnswer(
    val displayLabel: String,
    val simpleLabel: String
) {
    YES("Yes", "Yes"),
    NO("No", "No"),
    UNSURE("Unsure", "Not sure"),
    SKIPPED("Skipped", "Skipped");

    companion object {
        fun fromStorageKey(raw: String?): TriStateAnswer {
            if (raw.isNullOrBlank()) return SKIPPED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SKIPPED
        }
    }
}

/**
 * Optional injury body locations to enrich clinical cross-checking (e.g., hip/wrist/spine + osteoporosis).
 */
enum class InjuryLocation(val displayLabel: String, val isCriticalLocation: Boolean = false) {
    NONE("None", false),
    HEAD("Head", true),
    HIP("Hip / Pelvis", false),
    WRIST_ARM("Wrist / Arm", false),
    BACK_SPINE("Back / Spine", false),
    LEG_KNEE("Leg / Knee", false),
    CHEST_RIBS("Chest / Ribs", false),
    OTHER("Other", false);

    companion object {
        fun fromStorageKey(raw: String?): InjuryLocation? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Optional recovery / mobility check after a fall.
 */
enum class RecoveryAbility(val displayLabel: String, val isSevere: Boolean) {
    STOOD_UP_EASILY("Stood up easily", false),
    STOOD_UP_WITH_DIFFICULTY("Stood up with difficulty", false),
    UNABLE_TO_STAND("Unable to stand up", true),
    SKIPPED("Skipped", false);

    companion object {
        fun fromStorageKey(raw: String?): RecoveryAbility {
            if (raw.isNullOrBlank()) return SKIPPED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SKIPPED
        }
    }
}

/**
 * Completion status of a Post-Fall Assessment session.
 */
enum class AssessmentCompletionStatus(val displayLabel: String) {
    IN_PROGRESS("In progress"),
    FALSE_DETECTION_CLOSED("False detection recorded"),
    COMPLETED("Assessment completed"),
    SKIPPED("Skipped by user")
}

/**
 * 3-tier safety advisory classification produced by the Hybrid AI Health Cross-Check Engine.
 */
enum class HealthAdvisoryTier(
    val badgeLabel: String,
    val headlineDefault: String,
    val severityRank: Int
) {
    URGENT_WARNING(
        badgeLabel = "URGENT MEDICAL WARNING",
        headlineDefault = "Seek immediate emergency medical attention",
        severityRank = 3
    ),
    CAUTION_ADVISORY(
        badgeLabel = "CAUTION — PROMPT EVALUATION ADVISED",
        headlineDefault = "Consider speaking with a healthcare professional or trusted contact",
        severityRank = 2
    ),
    SELF_MONITORING(
        badgeLabel = "LOW IMMEDIATE RISK — MONITOR FOR 24–48H",
        headlineDefault = "No immediate red-flag symptoms reported — continue self-monitoring",
        severityRank = 1
    );

    companion object {
        fun fromStorageKey(raw: String?): HealthAdvisoryTier {
            if (raw.isNullOrBlank()) return SELF_MONITORING
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SELF_MONITORING
        }
    }
}

/**
 * Voluntary user Medical ID health profile.
 * All fields are optional and controlled by granular user consent toggles.
 */
data class MedicalIdProfile(
    val profileId: String = "local_default",
    val fullName: String = "",
    val dateOfBirth: String = "",
    val ageOrGroup: String = "",
    val bloodType: String = "",
    val knownConditions: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val mobilityOrFallHistory: String = "",
    val emergencyNotes: String = "",
    val allowAiCrossCheck: Boolean = true,
    val includeInEmergencyAlerts: Boolean = false,
    val syncToCloudFirestore: Boolean = true,
    val updatedAtEpochMillis: Long = 0L
) {
    val isConfigured: Boolean
        get() = fullName.isNotBlank() ||
            dateOfBirth.isNotBlank() ||
            ageOrGroup.isNotBlank() ||
            bloodType.isNotBlank() ||
            knownConditions.isNotEmpty() ||
            medications.isNotEmpty() ||
            allergies.isNotEmpty() ||
            mobilityOrFallHistory.isNotBlank() ||
            emergencyNotes.isNotBlank()

    private val combinedLowerText: String
        get() = (knownConditions + medications + listOf(mobilityOrFallHistory, emergencyNotes))
            .joinToString(" ")
            .lowercase()

    val hasBloodThinners: Boolean
        get() = listOf(
            "blood thinner", "anticoagulant", "warfarin", "apixaban", "eliquis",
            "rivaroxaban", "xarelto", "dabigatran", "pradaxa", "edoxaban",
            "clopidogrel", "plavix", "heparin", "antiplatelet", "aspirin"
        ).any { combinedLowerText.contains(it) }

    val hasDiabetesOrInsulin: Boolean
        get() = listOf(
            "diabetes", "diabetic", "insulin", "hypoglycemia", "low blood sugar",
            "metformin", "glipizide", "sulfonylurea"
        ).any { combinedLowerText.contains(it) }

    val hasCardiacOrHypertension: Boolean
        get() = listOf(
            "heart", "cardiac", "atrial fibrillation", "afib", "arrhythmia",
            "pacemaker", "hypertension", "high blood pressure", "low blood pressure",
            "hypotension", "beta blocker", "metoprolol", "lisinopril", "amlodipine",
            "diuretic", "furosemide", "coronary", "stroke", "tia"
        ).any { combinedLowerText.contains(it) }

    val hasOsteoporosisOrBoneFragility: Boolean
        get() = listOf(
            "osteoporosis", "osteopenia", "bone", "fracture", "arthritis",
            "joint replacement", "hip replacement"
        ).any { combinedLowerText.contains(it) }

    val hasBalanceOrNeurologicalDisorder: Boolean
        get() = listOf(
            "vertigo", "balance", "dizziness", "parkinson", "neuropathy",
            "epilepsy", "seizure", "dementia", "alzheimer", "multiple sclerosis",
            "cane", "walker", "wheelchair", "frequent falls", "prior fall"
        ).any { combinedLowerText.contains(it) }

    val isOlderAdult75Plus: Boolean
        get() {
            if (ageOrGroup.contains("75") || ageOrGroup.contains("80") || ageOrGroup.contains("85")) return true
            val ageNum = ageOrGroup.trim().toIntOrNull()
            if (ageNum != null && ageNum >= 75) return true
            return false
        }

    /**
     * Builds a concise, emergency-responder-friendly summary when [includeInEmergencyAlerts] is enabled.
     */
    fun formatEmergencyAlertSummary(): String? {
        if (!includeInEmergencyAlerts || !isConfigured) return null
        return buildString {
            appendLine("--- MEDICAL ID SUMMARY (User Consented) ---")
            if (fullName.isNotBlank() || dateOfBirth.isNotBlank() || ageOrGroup.isNotBlank()) {
                val parts = listOfNotNull(
                    fullName.takeIf { it.isNotBlank() },
                    dateOfBirth.takeIf { it.isNotBlank() }?.let { "DOB: $it" },
                    ageOrGroup.takeIf { it.isNotBlank() }?.let { "Age: $it" }
                )
                appendLine("Patient: ${parts.joinToString(" • ")}")
            }
            if (bloodType.isNotBlank()) {
                appendLine("Blood Type: $bloodType")
            }
            if (knownConditions.isNotEmpty()) {
                appendLine("Medical Conditions: ${knownConditions.joinToString(", ")}")
            }
            if (medications.isNotEmpty()) {
                appendLine("Current Medications: ${medications.joinToString(", ")}")
            }
            if (allergies.isNotEmpty()) {
                appendLine("Allergies: ${allergies.joinToString(", ")}")
            }
            if (mobilityOrFallHistory.isNotBlank()) {
                appendLine("Mobility / Fall History: $mobilityOrFallHistory")
            }
            if (emergencyNotes.isNotBlank()) {
                appendLine("Medical Notes: $emergencyNotes")
            }
        }.trim()
    }

    companion object {
        val SUGGESTED_CONDITIONS = listOf(
            "Hypertension (High BP)",
            "Atrial Fibrillation / Heart Condition",
            "Diabetes",
            "Osteoporosis / Low Bone Density",
            "Vertigo / Balance Disorder",
            "Prior Stroke / TIA",
            "Asthma / COPD",
            "Epilepsy / Seizure History",
            "Neuropathy"
        )

        val SUGGESTED_MEDICATIONS = listOf(
            "Blood Thinner / Anticoagulant (Eliquis, Xarelto, Warfarin)",
            "Daily Aspirin / Clopidogrel",
            "Blood Pressure Medication",
            "Beta Blocker",
            "Insulin / Diabetes Medication",
            "Diuretic (Water Pill)",
            "Sedative / Sleep Aid"
        )

        val SUGGESTED_ALLERGIES = listOf(
            "No Known Allergies",
            "Penicillin / Antibiotics",
            "Aspirin / NSAIDs",
            "Latex",
            "Contrast Dye",
            "Sulfa Drugs"
        )

        val BLOOD_TYPES = listOf(
            "Unknown", "O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-"
        )
    }
}

/**
 * Live interactive state for an active Post-Fall Assessment session.
 */
data class PostFallAssessmentDraft(
    val assessmentId: String = "pfa_${UUID.randomUUID().toString().take(10)}_${System.currentTimeMillis()}",
    val linkedEventId: String = "manual_${System.currentTimeMillis()}",
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
    val isManualSelfCheck: Boolean = false,
    val incidentVerification: IncidentVerificationType? = null,
    val feelingOkay: FeelingOkayStatus = FeelingOkayStatus.SKIPPED,
    val painLevel: PainLevel = PainLevel.SKIPPED,
    val difficultyBreathing: TriStateAnswer = TriStateAnswer.SKIPPED,
    val dizzyOrWeak: TriStateAnswer = TriStateAnswer.SKIPPED,
    val confusionOrDifficultyResponding: TriStateAnswer = TriStateAnswer.SKIPPED,
    val hitHead: TriStateAnswer = TriStateAnswer.SKIPPED,
    val needMedicalAssistance: TriStateAnswer = TriStateAnswer.SKIPPED,
    val injuryLocations: List<InjuryLocation> = emptyList(),
    val recoveryAbility: RecoveryAbility = RecoveryAbility.SKIPPED,
    val bleedingReported: Boolean = false,
    val userNotes: String = "",
    val autoSosDispatched: Boolean = false,
    val triggeredAlertId: String? = null
) {
    /**
     * Maps the internal [difficultyBreathing] field to the simplified Question 4
     * ("Are you breathing normally?" -> Yes / No / Not sure):
     * - difficultyBreathing == NO -> breathingNormally == YES
     * - difficultyBreathing == YES -> breathingNormally == NO (severe warning!)
     * - difficultyBreathing == UNSURE -> breathingNormally == UNSURE
     * - difficultyBreathing == SKIPPED -> breathingNormally == SKIPPED
     */
    val breathingNormally: TriStateAnswer
        get() = when (difficultyBreathing) {
            TriStateAnswer.NO -> TriStateAnswer.YES
            TriStateAnswer.YES -> TriStateAnswer.NO
            TriStateAnswer.UNSURE -> TriStateAnswer.UNSURE
            TriStateAnswer.SKIPPED -> TriStateAnswer.SKIPPED
        }

    /**
     * Updates Question 2 ("Are you feeling okay?") and keeps [dizzyOrWeak] synchronized
     * so Medical ID cross-checks (e.g., diabetes, blood pressure, balance history) work seamlessly.
     */
    fun withFeelingOkay(status: FeelingOkayStatus): PostFallAssessmentDraft {
        val mappedDizzyOrWeak = when (status) {
            FeelingOkayStatus.YES -> TriStateAnswer.NO
            FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE -> TriStateAnswer.UNSURE
            FeelingOkayStatus.NO -> TriStateAnswer.YES
            FeelingOkayStatus.SKIPPED -> TriStateAnswer.SKIPPED
        }
        return copy(
            feelingOkay = status,
            dizzyOrWeak = mappedDizzyOrWeak
        )
    }

    /**
     * Updates Question 4 ("Are you breathing normally?") and maps it to [difficultyBreathing].
     */
    fun withBreathingNormally(breathingNormalAnswer: TriStateAnswer): PostFallAssessmentDraft {
        val mappedDifficulty = when (breathingNormalAnswer) {
            TriStateAnswer.YES -> TriStateAnswer.NO
            TriStateAnswer.NO -> TriStateAnswer.YES
            TriStateAnswer.UNSURE -> TriStateAnswer.UNSURE
            TriStateAnswer.SKIPPED -> TriStateAnswer.SKIPPED
        }
        return copy(difficultyBreathing = mappedDifficulty)
    }

    /**
     * Returns true if the user selected any critical/severe physical symptom that requires
     * immediate emergency medical advice and (per user preference) immediate automatic SOS dispatch.
     */
    fun hasSevereWarningIndicators(): Boolean {
        return painLevel == PainLevel.SEVERE_PAIN ||
            difficultyBreathing == TriStateAnswer.YES ||
            confusionOrDifficultyResponding == TriStateAnswer.YES ||
            hitHead == TriStateAnswer.YES ||
            needMedicalAssistance == TriStateAnswer.YES ||
            injuryLocations.contains(InjuryLocation.HEAD) ||
            recoveryAbility == RecoveryAbility.UNABLE_TO_STAND ||
            bleedingReported
    }

    /**
     * Returns true when Question 3 ("Severe" pain) or Question 4 ("No" to breathing normally)
     * requires immediately stopping the questionnaire to show emergency guidance.
     */
    fun requiresImmediateEmergencyStop(): Boolean {
        return painLevel == PainLevel.SEVERE_PAIN ||
            difficultyBreathing == TriStateAnswer.YES
    }

    /**
     * Human-readable list of severe symptoms selected by the user so far.
     */
    fun describeSevereIndicators(): List<String> {
        val list = mutableListOf<String>()
        if (painLevel == PainLevel.SEVERE_PAIN) list.add("Severe pain")
        if (difficultyBreathing == TriStateAnswer.YES) list.add("Difficulty breathing")
        if (confusionOrDifficultyResponding == TriStateAnswer.YES) list.add("Confusion or difficulty responding")
        if (hitHead == TriStateAnswer.YES || injuryLocations.contains(InjuryLocation.HEAD)) list.add("Head impact reported")
        if (bleedingReported) list.add("Bleeding reported")
        if (recoveryAbility == RecoveryAbility.UNABLE_TO_STAND) list.add("Unable to stand up")
        if (needMedicalAssistance == TriStateAnswer.YES) list.add("Requested medical assistance")
        return list
    }

    /**
     * Returns true if the user has answered at least one physical condition question.
     */
    val hasAnyPhysicalAnswers: Boolean
        get() = feelingOkay != FeelingOkayStatus.SKIPPED ||
            painLevel != PainLevel.SKIPPED ||
            difficultyBreathing != TriStateAnswer.SKIPPED ||
            dizzyOrWeak != TriStateAnswer.SKIPPED ||
            confusionOrDifficultyResponding != TriStateAnswer.SKIPPED ||
            hitHead != TriStateAnswer.SKIPPED ||
            needMedicalAssistance != TriStateAnswer.SKIPPED ||
            injuryLocations.isNotEmpty() ||
            recoveryAbility != RecoveryAbility.SKIPPED ||
            bleedingReported ||
            userNotes.isNotBlank()
}

/**
 * Structured output from the Hybrid AI Health Cross-Check Engine (deterministic clinical rules + Gemini).
 */
data class HealthCrossCheckResult(
    val assessmentId: String,
    val linkedEventId: String,
    val evaluatedAtEpochMillis: Long = System.currentTimeMillis(),
    val riskTier: HealthAdvisoryTier,
    val advisoryHeadline: String,
    val advisoryExplanation: String,
    val crossCheckHighlights: List<String>,
    val recommendedNextSteps: List<String>,
    val severeSymptomDetected: Boolean,
    val autoSosTriggered: Boolean,
    val triggeredAlertId: String? = null,
    val usedMedicalIdContext: Boolean,
    val evaluationSource: String,
    val nonDiagnosticDisclaimer: String = DEFAULT_MEDICAL_DISCLAIMER
) {
    companion object {
        const val DEFAULT_MEDICAL_DISCLAIMER =
            "Important Medical Disclaimer: SoloSignal AI provides safety triage guidance only and is NOT a medical device or a substitute for professional medical diagnosis. If you feel unwell or symptoms worsen, contact emergency services (911 / 999 / 112) or a physician immediately."
    }
}

/**
 * JSON serialization helpers for list columns in Room.
 */
object PostFallJsonCodec {
    fun encodeStringList(items: List<String>): String {
        val arr = JSONArray()
        for (item in items) {
            if (item.isNotBlank()) arr.put(item.trim())
        }
        return arr.toString()
    }

    fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeAssessmentSummaryJson(
        draft: PostFallAssessmentDraft,
        result: HealthCrossCheckResult?
    ): String {
        return try {
            JSONObject().apply {
                put("assessmentId", draft.assessmentId)
                put("linkedEventId", draft.linkedEventId)
                put("incidentVerification", draft.incidentVerification?.displayLabel ?: "Not specified")
                put("isFalseDetection", draft.incidentVerification?.isFalseDetection == true)
                put("isPhoneDrop", draft.incidentVerification?.isPhoneDrop == true)
                put("painLevel", draft.painLevel.displayLabel)
                put("difficultyBreathing", draft.difficultyBreathing.displayLabel)
                put("dizzyOrWeak", draft.dizzyOrWeak.displayLabel)
                put("confusionOrDifficultyResponding", draft.confusionOrDifficultyResponding.displayLabel)
                put("hitHead", draft.hitHead.displayLabel)
                put("needMedicalAssistance", draft.needMedicalAssistance.displayLabel)
                put("recoveryAbility", draft.recoveryAbility.displayLabel)
                put("bleedingReported", draft.bleedingReported)
                put("injuryLocations", JSONArray(draft.injuryLocations.map { it.displayLabel }))
                put("userNotes", draft.userNotes)
                put("autoSosDispatched", draft.autoSosDispatched)
                if (result != null) {
                    put("riskTier", result.riskTier.name)
                    put("riskTierBadge", result.riskTier.badgeLabel)
                    put("advisoryHeadline", result.advisoryHeadline)
                    put("advisoryExplanation", result.advisoryExplanation)
                    put("crossCheckHighlights", JSONArray(result.crossCheckHighlights))
                    put("recommendedNextSteps", JSONArray(result.recommendedNextSteps))
                    put("usedMedicalIdContext", result.usedMedicalIdContext)
                    put("evaluationSource", result.evaluationSource)
                }
            }.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
