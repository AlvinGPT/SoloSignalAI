package com.example.domain

import com.example.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hybrid Post-Fall Health Cross-Check Engine:
 * 1. Deterministic, conservative clinical warning-rule engine (instant, 100% offline-capable,
 *    enforces strict clinical escalation floors when severe symptoms or high-risk Medical ID
 *    interactions are present).
 * 2. Optional Gemini structured cross-check synthesis when consented and online, which enriches
 *    explanations and next steps while NEVER downgrading the deterministic severity tier.
 */
object AiHealthCrossCheckEngine {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Synchronous deterministic evaluation suitable for instant UI previews, unit tests, and
     * offline fallback.
     */
    fun evaluateDeterministic(
        draft: PostFallAssessmentDraft,
        medicalId: MedicalIdProfile?
    ): HealthCrossCheckResult {
        val useMedicalId = medicalId != null && medicalId.allowAiCrossCheck && medicalId.isConfigured
        val profile = if (useMedicalId) medicalId else null

        var tier = HealthAdvisoryTier.SELF_MONITORING
        val highlights = mutableListOf<String>()
        val nextSteps = mutableListOf<String>()

        fun escalateTo(target: HealthAdvisoryTier) {
            if (target.severityRank > tier.severityRank) {
                tier = target
            }
        }

        // 1. Direct severe physical symptoms reported in Q1–Q5
        if (draft.painLevel == PainLevel.SEVERE_PAIN) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            highlights.add("You reported severe pain. Please get emergency medical help right away to check for serious injury.")
        }

        if (draft.difficultyBreathing == TriStateAnswer.YES) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            if (profile?.hasCardiacOrHypertension == true) {
                highlights.add("Trouble breathing with your heart or blood pressure history in Medical ID is an urgent warning sign.")
            } else {
                highlights.add("Trouble breathing after a fall is an urgent warning sign. Please get medical help right away.")
            }
        } else if (draft.difficultyBreathing == TriStateAnswer.UNSURE) {
            escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
            highlights.add("You selected 'Not sure' about breathing normally — rest and watch closely for any shortness of breath.")
        }

        if (draft.confusionOrDifficultyResponding == TriStateAnswer.YES) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            highlights.add("Feeling confused or having trouble responding after a fall requires immediate medical care.")
        } else if (draft.confusionOrDifficultyResponding == TriStateAnswer.UNSURE) {
            escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
            highlights.add("Feeling unsure or foggy warrants having a family member or doctor check on you soon.")
        }

        val headImpactReported = draft.hitHead == TriStateAnswer.YES ||
            draft.injuryLocations.contains(InjuryLocation.HEAD)
        val headImpactUnsure = draft.hitHead == TriStateAnswer.UNSURE

        if (headImpactReported) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            if (profile?.hasBloodThinners == true) {
                highlights.add(
                    "IMPORTANT MEDICAL ID CHECK: You reported hitting your head and your Medical ID lists a blood thinner (anticoagulant). Even a small bump on the head with blood thinners requires urgent medical care."
                )
            } else {
                highlights.add("Hitting your head during a fall requires immediate medical care.")
            }
        } else if (headImpactUnsure) {
            if (profile?.hasBloodThinners == true) {
                escalateTo(HealthAdvisoryTier.URGENT_WARNING)
                highlights.add(
                    "IMPORTANT MEDICAL ID CHECK: You are not sure if you hit your head and your Medical ID lists a blood thinner (anticoagulant). Urgent medical care is strongly advised."
                )
            } else {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add("Because you are not sure if you hit your head, watch closely for headache, nausea, or sleepiness.")
            }
        }

        if (draft.bleedingReported) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            if (profile?.hasBloodThinners == true) {
                highlights.add("Bleeding while taking a blood thinner (anticoagulant) in your Medical ID requires urgent medical help.")
            } else {
                highlights.add("Bleeding after a fall should be checked by a doctor right away.")
            }
        }

        if (draft.recoveryAbility == RecoveryAbility.UNABLE_TO_STAND) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            highlights.add("Being unable to stand up safely is an urgent sign — stay where you are and wait for help.")
        } else if (draft.recoveryAbility == RecoveryAbility.STOOD_UP_WITH_DIFFICULTY) {
            escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
            highlights.add("Having trouble standing up suggests you should rest and ask someone for help before walking.")
        }

        if (draft.needMedicalAssistance == TriStateAnswer.YES) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            highlights.add("You asked for help right now. Use the large buttons below to call Emergency Services or alert your contact.")
        } else if (draft.needMedicalAssistance == TriStateAnswer.UNSURE) {
            escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
            highlights.add("You selected 'Not sure' about needing help — calling a family member or doctor is the safest choice.")
        }

        // 2. Question 2 ("Are you feeling okay?") & Dizziness / Weakness cross-checked with Medical ID
        val feelingUnwell = draft.feelingOkay == FeelingOkayStatus.NO ||
            draft.feelingOkay == FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE ||
            draft.dizzyOrWeak == TriStateAnswer.YES ||
            draft.dizzyOrWeak == TriStateAnswer.UNSURE

        if (draft.feelingOkay == FeelingOkayStatus.NO && profile?.hasBloodThinners == true) {
            escalateTo(HealthAdvisoryTier.URGENT_WARNING)
            highlights.add(
                "MEDICAL ID CHECK: Not feeling okay after a fall while taking a blood thinner (anticoagulant) should be checked by a doctor right away."
            )
        }

        if (feelingUnwell) {
            if (profile?.hasDiabetesOrInsulin == true) {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                if ((draft.dizzyOrWeak == TriStateAnswer.YES || draft.feelingOkay == FeelingOkayStatus.NO) &&
                    draft.painLevel == PainLevel.MODERATE_PAIN
                ) {
                    escalateTo(HealthAdvisoryTier.URGENT_WARNING)
                }
                highlights.add(
                    "MEDICAL ID CHECK: Not feeling okay or feeling dizzy with diabetes in your Medical ID may be linked to low blood sugar. Sit down and check your blood sugar if you can."
                )
            }
            if (profile?.hasCardiacOrHypertension == true) {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add(
                    "MEDICAL ID CHECK: Feeling unwell or dizzy with heart or blood pressure medicine in your Medical ID can happen if your blood pressure drops. Stay seated and rest."
                )
            }
            if (profile?.hasBalanceOrNeurologicalDisorder == true) {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add(
                    "MEDICAL ID CHECK: Feeling uncomfortable or unsteady with your balance history increases the chance of falling again if you stand up too quickly."
                )
            }
            if (profile == null || (!profile.hasDiabetesOrInsulin && !profile.hasCardiacOrHypertension && !profile.hasBalanceOrNeurologicalDisorder)) {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add("Not feeling completely okay after a fall means you should sit down, rest, and avoid walking alone right now.")
            }
        }

        // 3. Moderate / Mild Pain + Bone Fragility (Osteoporosis / Age 75+)
        val hasHighRiskBoneLocation = draft.injuryLocations.any {
            it == InjuryLocation.HIP || it == InjuryLocation.BACK_SPINE || it == InjuryLocation.WRIST_ARM || it == InjuryLocation.CHEST_RIBS
        }
        if (draft.painLevel == PainLevel.MODERATE_PAIN) {
            if ((profile?.hasOsteoporosisOrBoneFragility == true || profile?.isOlderAdult75Plus == true) &&
                (hasHighRiskBoneLocation || draft.incidentVerification == IncidentVerificationType.EXPERIENCED_FALL)
            ) {
                escalateTo(HealthAdvisoryTier.URGENT_WARNING)
                highlights.add(
                    "MEDICAL ID CHECK: Moderate pain after a fall combined with osteoporosis or bone fragility in your Medical ID can hide a bone fracture. Please have a doctor check you promptly."
                )
            } else {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add("Moderate pain after a fall should be checked by a doctor to make sure nothing is sprained or broken.")
            }
        } else if (draft.painLevel == PainLevel.MILD_PAIN) {
            if (profile?.hasOsteoporosisOrBoneFragility == true) {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add(
                    "MEDICAL ID CHECK: Even a little pain after a fall with osteoporosis in your Medical ID is worth sharing with your doctor or family contact."
                )
            } else {
                escalateTo(HealthAdvisoryTier.CAUTION_ADVISORY)
                highlights.add("You reported a little pain. Rest comfortably and watch if the soreness gets worse.")
            }
        }

        // 4. Populate tier-appropriate headline, explanation, and recommended next steps in simple English
        val headline: String
        val explanation: String

        when (tier) {
            HealthAdvisoryTier.URGENT_WARNING -> {
                headline = if (draft.autoSosDispatched) {
                    "Please Get Help Now — Emergency Alert Sent"
                } else {
                    "Please Get Medical Help Right Away"
                }
                explanation = buildString {
                    append("Your answers")
                    if (profile != null) append(" and your saved Medical ID")
                    append(" show warning signs that need immediate attention. Please stay seated or lying down and do not try to walk alone.")
                }
                nextSteps.add("Tap 'Call Emergency Services' below or ask someone nearby to help you.")
                if (draft.autoSosDispatched) {
                    nextSteps.add("Your emergency contact has already been sent an automatic SOS alert.")
                } else {
                    nextSteps.add("Tap 'Send Alert to Contact' below so your family or trusted contact knows right away.")
                }
                nextSteps.add("Stay still and comfortable until help arrives.")
                if (profile != null && profile.allergies.isNotEmpty()) {
                    nextSteps.add("Medical ID Allergies noted for helpers: ${profile.allergies.joinToString(", ")}.")
                }
            }

            HealthAdvisoryTier.CAUTION_ADVISORY -> {
                headline = "Please Rest and Check In With Someone"
                explanation = buildString {
                    append("Based on your answers")
                    if (profile != null) append(" and your Medical ID")
                    append(", it is safest to sit down, rest, and let a doctor or family member know how you are feeling today.")
                }
                nextSteps.add("Sit or rest comfortably for 15 to 20 minutes before standing up.")
                nextSteps.add("Call a family member, trusted contact, or your doctor to let them know what happened.")
                nextSteps.add("Get urgent help right away if pain gets worse or you feel dizzy, confused, or short of breath.")
            }

            HealthAdvisoryTier.SELF_MONITORING -> {
                headline = "No Urgent Warning Signs Reported — Take It Slow"
                explanation = buildString {
                    append("You did not report pain, breathing trouble, or needing help right now.")
                    if (profile?.hasBloodThinners == true) {
                        append(" Because your Medical ID lists a blood thinner (anticoagulant), please watch for any bruising or soreness over the next 2 days.")
                    } else {
                        append(" Even when you feel okay right after a fall, soreness can appear over the next day or two.")
                    }
                }
                if (highlights.isEmpty()) {
                    highlights.add("You reported feeling okay, breathing normally, and having no pain.")
                    if (profile != null) {
                        highlights.add("Checked against your saved Medical ID profile.")
                    }
                }
                nextSteps.add("Take your time getting up slowly and drink some water.")
                nextSteps.add("Pay attention to how you feel over the next 24 to 48 hours.")
                nextSteps.add("If you start to feel unwell later, ask for help right away.")
            }
        }

        return HealthCrossCheckResult(
            assessmentId = draft.assessmentId,
            linkedEventId = draft.linkedEventId,
            riskTier = tier,
            advisoryHeadline = headline,
            advisoryExplanation = explanation,
            crossCheckHighlights = highlights.distinct(),
            recommendedNextSteps = nextSteps.distinct(),
            severeSymptomDetected = draft.hasSevereWarningIndicators(),
            autoSosTriggered = draft.autoSosDispatched,
            triggeredAlertId = draft.triggeredAlertId,
            usedMedicalIdContext = profile != null,
            evaluationSource = "ON_DEVICE_CLINICAL_RULES"
        )
    }

    /**
     * Runs the full Hybrid AI Health Cross-Check:
     * 1. Computes the deterministic clinical safety floor (`evaluateDeterministic`).
     * 2. If `BuildConfig.GEMINI_API_KEY` is configured and Medical ID / AI Cross-Check is permitted,
     *    requests structured JSON enrichment from Gemini (`gemini-2.5-flash`) and merges the result
     *    without ever lowering the deterministic `HealthAdvisoryTier`.
     */
    suspend fun evaluateHybrid(
        draft: PostFallAssessmentDraft,
        medicalId: MedicalIdProfile?
    ): HealthCrossCheckResult = withContext(Dispatchers.IO) {
        val deterministic = evaluateDeterministic(draft, medicalId)
        val apiKey = BuildConfig.GEMINI_API_KEY
        val canCallGemini = apiKey.isNotBlank() &&
            apiKey != "MY_GEMINI_API_KEY" &&
            !apiKey.startsWith("YOUR_") &&
            (medicalId == null || medicalId.allowAiCrossCheck)

        if (!canCallGemini) {
            return@withContext deterministic
        }

        return@withContext try {
            val prompt = buildGeminiCrossCheckPrompt(draft, medicalId, deterministic)
            val requestBodyJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.15)
                    }
                )
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use deterministic
                val rawBody = response.body?.string() ?: return@use deterministic
                val root = JSONObject(rawBody)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?: return@use deterministic

                val parsed = JSONObject(text)
                val geminiTier = HealthAdvisoryTier.fromStorageKey(parsed.optString("riskTier"))
                // Never downgrade below the deterministic clinical safety floor
                val finalTier = if (geminiTier.severityRank > deterministic.riskTier.severityRank) {
                    geminiTier
                } else {
                    deterministic.riskTier
                }

                val geminiHeadline = parsed.optString("advisoryHeadline").trim()
                val geminiExplanation = parsed.optString("advisoryExplanation").trim()
                val geminiHighlights = mutableListOf<String>()
                parsed.optJSONArray("crossCheckHighlights")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotBlank()) geminiHighlights.add(item)
                    }
                }
                val geminiSteps = mutableListOf<String>()
                parsed.optJSONArray("recommendedNextSteps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotBlank()) geminiSteps.add(item)
                    }
                }

                val mergedHighlights = (deterministic.crossCheckHighlights + geminiHighlights).distinct().take(5)
                val mergedSteps = (deterministic.recommendedNextSteps + geminiSteps).distinct().take(5)

                deterministic.copy(
                    riskTier = finalTier,
                    advisoryHeadline = geminiHeadline.ifBlank { deterministic.advisoryHeadline },
                    advisoryExplanation = geminiExplanation.ifBlank { deterministic.advisoryExplanation },
                    crossCheckHighlights = mergedHighlights.ifEmpty { deterministic.crossCheckHighlights },
                    recommendedNextSteps = mergedSteps.ifEmpty { deterministic.recommendedNextSteps },
                    evaluationSource = "HYBRID_GEMINI_AND_RULES"
                )
            }
        } catch (_: Exception) {
            deterministic
        }
    }

    private fun buildGeminiCrossCheckPrompt(
        draft: PostFallAssessmentDraft,
        medicalId: MedicalIdProfile?,
        deterministicFloor: HealthCrossCheckResult
    ): String {
        val useMedicalId = medicalId != null && medicalId.allowAiCrossCheck && medicalId.isConfigured
        return buildString {
            appendLine("You are a cautious, non-diagnostic post-fall safety triage assistant inside the SoloSignal AI app.")
            appendLine("Cross-check the user's post-fall responses with their voluntary Medical ID profile.")
            appendLine("RULES:")
            appendLine("1. Never claim to diagnose a disease or fracture, and never tell the user to stop or change prescribed medication dosages.")
            appendLine("2. Use calm, supportive, non-diagnostic phrasing ('may increase risk', 'warrants prompt medical evaluation').")
            appendLine("3. Minimum allowed riskTier floor from clinical safety rules is: ${deterministicFloor.riskTier.name}.")
            appendLine()
            appendLine("POST-FALL ASSESSMENT RESPONSES:")
            appendLine("- Incident Verification: ${draft.incidentVerification?.displayLabel ?: "Possible fall"}")
            appendLine("- Pain Level: ${draft.painLevel.displayLabel}")
            appendLine("- Injury Locations: ${draft.injuryLocations.joinToString { it.displayLabel }.ifBlank { "None specified" }}")
            appendLine("- Difficulty Breathing: ${draft.difficultyBreathing.displayLabel}")
            appendLine("- Dizzy or Unusually Weak: ${draft.dizzyOrWeak.displayLabel}")
            appendLine("- Confusion or Difficulty Responding: ${draft.confusionOrDifficultyResponding.displayLabel}")
            appendLine("- Hit Head: ${draft.hitHead.displayLabel}")
            appendLine("- Bleeding Reported: ${if (draft.bleedingReported) "Yes" else "No"}")
            appendLine("- Recovery / Ability to Stand: ${draft.recoveryAbility.displayLabel}")
            appendLine("- Need Medical Assistance: ${draft.needMedicalAssistance.displayLabel}")
            if (draft.userNotes.isNotBlank()) {
                appendLine("- User Notes: ${draft.userNotes}")
            }
            appendLine()
            if (useMedicalId && medicalId != null) {
                appendLine("USER MEDICAL ID PROFILE (Consented for Cross-Check):")
                if (medicalId.ageOrGroup.isNotBlank() || medicalId.dateOfBirth.isNotBlank()) {
                    appendLine("- Age / DOB: ${listOf(medicalId.ageOrGroup, medicalId.dateOfBirth).filter { it.isNotBlank() }.joinToString(" / ")}")
                }
                if (medicalId.bloodType.isNotBlank()) appendLine("- Blood Type: ${medicalId.bloodType}")
                if (medicalId.knownConditions.isNotEmpty()) appendLine("- Known Conditions: ${medicalId.knownConditions.joinToString(", ")}")
                if (medicalId.medications.isNotEmpty()) appendLine("- Medications: ${medicalId.medications.joinToString(", ")}")
                if (medicalId.allergies.isNotEmpty()) appendLine("- Allergies: ${medicalId.allergies.joinToString(", ")}")
                if (medicalId.mobilityOrFallHistory.isNotBlank()) appendLine("- Mobility / Fall History: ${medicalId.mobilityOrFallHistory}")
                if (medicalId.emergencyNotes.isNotBlank()) appendLine("- Notes: ${medicalId.emergencyNotes}")
            } else {
                appendLine("USER MEDICAL ID PROFILE: Not provided or not consented.")
            }
            appendLine()
            appendLine("Return ONLY a JSON object with keys:")
            appendLine("""{"riskTier": "URGENT_WARNING|CAUTION_ADVISORY|SELF_MONITORING", "advisoryHeadline": "string", "advisoryExplanation": "string", "crossCheckHighlights": ["string"], "recommendedNextSteps": ["string"]}""")
        }
    }
}
