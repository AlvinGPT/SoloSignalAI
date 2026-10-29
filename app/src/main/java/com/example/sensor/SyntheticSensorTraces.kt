package com.example.sensor

import com.example.BuildConfig
import com.example.domain.MotionSample
import kotlin.math.cos
import kotlin.math.sin

enum class SyntheticScenario(
    val title: String,
    val subtitle: String,
    val expectedOutcome: String,
    val shouldTriggerCandidateCheckIn: Boolean
) {
    STAGED_POSSIBLE_FALL(
        title = "Staged Possible Fall (Forward Trip)",
        subtitle = "Free-fall dip (<6 m/s² for 100ms) → 65 m/s² impact within 1s → 2.5s stillness (Triggers check-in)",
        expectedOutcome = "Triggers 'Possible fall detected' 15s check-in",
        shouldTriggerCandidateCheckIn = true
    ),
    BACKWARD_SLIP_FALL(
        title = "Backward Slip Fall",
        subtitle = "Free-fall dip (<6 m/s² for 100ms) → 68 m/s² impact → 2.5s stillness",
        expectedOutcome = "Triggers 'Possible fall detected' 15s check-in",
        shouldTriggerCandidateCheckIn = true
    ),
    COLLAPSE_WITHOUT_FREE_FALL(
        title = "Gradual Collapse (No Free-Fall)",
        subtitle = "No free-fall dip (min mag stays ~7.4 m/s²) → impact alone does not trigger",
        expectedOutcome = "Rejected: Free-fall phase required (does NOT trigger alert)",
        shouldTriggerCandidateCheckIn = false
    ),
    PHONE_DROPPED_FLOOR(
        title = "Phone Dropped on Floor (Synthetic)",
        subtitle = "Free-fall drop (<6 m/s² for 150ms) → 64 m/s² floor impact → 2.5s stillness",
        expectedOutcome = "Triggers 15s check-in",
        shouldTriggerCandidateCheckIn = true
    ),
    PLACING_PHONE_ON_TABLE(
        title = "Placing Phone Firmly on Table",
        subtitle = "Hand-guided placement with 18-22 m/s² Z-axis tap → immediate stillness (No free-fall, peak <30)",
        expectedOutcome = "Rejected: No free-fall dip and peak below 30 m/s² (does NOT trigger alert)",
        shouldTriggerCandidateCheckIn = false
    ),
    NORMAL_WALKING(
        title = "Normal Walking",
        subtitle = "Rhythmic 8.2–12.8 m/s² step oscillations with upright orientation",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    RUNNING_JOGGING(
        title = "Running / Jogging",
        subtitle = "Repeated foot-strike peaks up to ~45 m/s² with continuous rhythmic motion",
        expectedOutcome = "Rejected: Peak <50 m/s² and continuous active movement (does NOT trigger alert)",
        shouldTriggerCandidateCheckIn = false
    ),
    SITTING_DOWN_QUICKLY(
        title = "Sitting Down Quickly",
        subtitle = "Moderate 15.8 m/s² cushioned deceleration without fall impact",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    BENDING_OVER_PICKUP(
        title = "Bending Over to Pick Up Object",
        subtitle = "Torso bend and recovery without free-fall or 30 m/s² impact",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    LYING_DOWN_INTENTIONALLY(
        title = "Lying Down Intentionally",
        subtitle = "Smooth orientation shift with gentle <12.5 m/s² acceleration",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    JUMPING_EXERCISE(
        title = "Jumping / Active Exercise",
        subtitle = "Landing peaks followed by continuous active motion",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    SHAKING_PHONE(
        title = "Shaking Phone in Hand",
        subtitle = "Rapid back-and-forth shaking without free-fall or stillness",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    ),
    SINGLE_NOISY_SPIKE(
        title = "Single Isolated Peak (Noisy Sensor)",
        subtitle = "One isolated sample surrounded by calm gravity readings",
        expectedOutcome = "Rejected: Isolated peak without free-fall or stillness (does NOT trigger)",
        shouldTriggerCandidateCheckIn = false
    ),
    ORIENTATION_CHANGE_ONLY(
        title = "Orientation Change Without Fall",
        subtitle = "Rotating phone 90° in hand from portrait to landscape at ~9.8 m/s²",
        expectedOutcome = "Does NOT trigger alert",
        shouldTriggerCandidateCheckIn = false
    )
}

object SyntheticSensorTraces {
    const val STEP_NANOS = 50_000_000L // 50 ms (20 Hz synthetic trace step)

    enum class TraceType(
        val title: String,
        val subtitle: String,
        val mappedScenario: SyntheticScenario
    ) {
        NORMAL_WALKING(
            title = "Normal Walking",
            subtitle = "Everyday walking rhythm — does NOT trigger",
            mappedScenario = SyntheticScenario.NORMAL_WALKING
        ),
        SITTING_DOWN_QUICKLY(
            title = "Sitting Down Quickly",
            subtitle = "Cushioned deceleration — does NOT trigger",
            mappedScenario = SyntheticScenario.SITTING_DOWN_QUICKLY
        ),
        PLACING_ON_TABLE(
            title = "Placing Firmly on Table",
            subtitle = "Sharp desk tap + flat stillness — does NOT trigger",
            mappedScenario = SyntheticScenario.PLACING_PHONE_ON_TABLE
        ),
        PHONE_SHAKEN_IN_HAND(
            title = "Active Exercise / Shaking",
            subtitle = "High peaks without post-event stillness — does NOT trigger",
            mappedScenario = SyntheticScenario.JUMPING_EXERCISE
        ),
        BRIEF_BUMP_CONTAINED(
            title = "Single Isolated Spike",
            subtitle = "Single noisy sample — rejected without 3-phase sequence",
            mappedScenario = SyntheticScenario.SINGLE_NOISY_SPIKE
        ),
        FALL_LIKE_IMPACT_STILLNESS(
            title = "Possible Fall (Free-Fall + Impact + Stillness)",
            subtitle = "Dip <6 m/s² (100ms) → 65 m/s² impact → 2.5s stillness (Triggers check-in)",
            mappedScenario = SyntheticScenario.STAGED_POSSIBLE_FALL
        )
    }

    fun generateTrace(traceType: TraceType, baseTimestampNanos: Long = 1_000_000_000L): List<MotionSample> {
        return generateTrace(traceType.mappedScenario, baseTimestampNanos)
    }

    /**
     * Generates a 75-sample synthetic trace (3.75s total duration at 50ms intervals)
     * matching the requested scenario and allowing the full 2.5s stillness phase to be observed.
     */
    fun generateTrace(
        scenario: SyntheticScenario,
        baseTimestampNanos: Long = 1_000_000_000L
    ): List<MotionSample> {
        val totalSamples = 75
        val list = ArrayList<MotionSample>(totalSamples)

        for (i in 0 until totalSamples) {
            val ts = baseTimestampNanos + i * STEP_NANOS
            var gx = 0f
            var gy = 0f
            var gz = 0f

            val (ax, ay, az) = when (scenario) {
                SyntheticScenario.STAGED_POSSIBLE_FALL -> {
                    when (i) {
                        in 0..11 -> { // 0 to 550 ms: resting flat
                            Triple(0.2f, 0.2f, 9.81f)
                        }
                        in 12..14 -> { // 600 to 700 ms: Free-fall dip (<6.0 m/s² for 100ms >= 40ms)
                            Triple(0.3f, 1.2f, 1.0f) // Magnitude ~1.6 m/s²
                        }
                        15 -> { // 750 ms: High impact peak >= 60.0 m/s² within 1000ms
                            Triple(12.0f, 25.0f, 58.8f) // Magnitude = sqrt(144 + 625 + 3457.44) = 65.0 m/s²
                        }
                        16 -> { // 800 ms: Immediate post-impact rebound settling into stillness
                            Triple(0.5f, 1.2f, 9.6f) // Magnitude ~9.7 m/s²
                        }
                        else -> { // 850 to 3700 ms: Stillness (58 samples = 2900ms >= 2500ms) within 9.81 ± 2.0 m/s²
                            val noise = if (i % 2 == 0) 0.05f else -0.05f
                            Triple(0.1f + noise, 0.1f, 9.81f + noise)
                        }
                    }
                }

                SyntheticScenario.BACKWARD_SLIP_FALL -> {
                    when (i) {
                        in 0..10 -> Triple(0.2f, 0.3f, 9.80f)
                        in 11..13 -> { // Free-fall dip < 6.0 m/s² for 100 ms
                            Triple(0.4f, 0.8f, 1.2f) // Magnitude ~1.5 m/s²
                        }
                        14 -> { // Impact peak >= 60 m/s²
                            Triple(15.0f, 20.0f, 63.2f) // Magnitude = 68.0 m/s²
                        }
                        15 -> Triple(0.6f, 1.0f, 9.7f)
                        else -> { // Post-impact stillness
                            val shift = if (i % 3 == 0) 0.08f else -0.04f
                            Triple(0.2f + shift, 0.2f, 9.81f + shift)
                        }
                    }
                }

                SyntheticScenario.COLLAPSE_WITHOUT_FREE_FALL -> {
                    // Knees giving out without free-fall: min magnitude stays ~7.4 m/s² (above 6.0 m/s² threshold)
                    when (i) {
                        in 0..11 -> Triple(0.2f, 0.2f, 9.81f)
                        in 12..14 -> Triple(2.5f, 5.5f, 4.4f) // Magnitude ~7.47 m/s² (NO dip below 6.0)
                        15 -> Triple(12.0f, 25.0f, 58.8f) // 65.0 m/s² impact
                        else -> Triple(0.1f, 0.1f, 9.81f)
                    }
                }

                SyntheticScenario.PHONE_DROPPED_FLOOR -> {
                    when (i) {
                        in 0..10 -> Triple(0.2f, 0.2f, 9.81f)
                        in 11..14 -> Triple(0.2f, 0.8f, 0.5f) // Free-fall ~0.96 m/s² for 150 ms
                        15 -> Triple(10.0f, 20.0f, 60.0f) // Magnitude ~64.0 m/s²
                        else -> Triple(0.1f, 0.1f, 9.81f)
                    }
                }

                SyntheticScenario.PLACING_PHONE_ON_TABLE -> {
                    // Guided hand-to-desk placement: No free-fall dip, spike ~20 m/s², then flat rest
                    when (i) {
                        in 0..14 -> Triple(0.3f, 6.8f, 7.0f) // Resting/holding in hand (~9.76 m/s²)
                        15 -> Triple(0.4f, 4.8f, 8.2f) // Lowering by hand (no free-fall)
                        16 -> Triple(0.6f, 2.2f, 20.5f) // Contact tap: ~20.6 m/s² (well below 60.0 m/s²)
                        else -> Triple(0.02f, 0.03f, 9.81f) // Dead-flat table rest
                    }
                }

                SyntheticScenario.NORMAL_WALKING -> {
                    val phase = i * 0.55
                    gx = (0.35 * sin(phase)).toFloat()
                    gy = (0.25 * cos(phase)).toFloat()
                    val axVal = (1.2 * sin(phase)).toFloat()
                    val ayVal = (9.81 + 2.6 * cos(phase)).toFloat()
                    val azVal = (0.9 * sin(phase * 0.5)).toFloat()
                    Triple(axVal, ayVal, azVal)
                }

                SyntheticScenario.RUNNING_JOGGING -> {
                    val cycle = i % 5
                    when (cycle) {
                        0 -> Triple(4.0f, 42.0f, 5.0f) // Foot-strike peak ~42.5 m/s² (peaks up to ~45 m/s²)
                        1 -> Triple(2.0f, 22.0f, 3.0f)
                        2 -> Triple(-1.8f, 7.2f, -1.5f)
                        3 -> Triple(-3.0f, 38.0f, -4.0f)
                        else -> Triple(1.1f, 11.4f, 1.6f)
                    }
                }

                SyntheticScenario.SITTING_DOWN_QUICKLY -> {
                    when (i) {
                        in 0..14 -> Triple(0.2f, 9.8f, 0.5f)
                        in 15..18 -> Triple(0.4f, 7.2f, 1.8f)
                        in 19..22 -> Triple(0.8f, 15.8f, 3.2f) // Cushioned deceleration ~16.1 m/s²
                        else -> Triple(0.3f, 9.2f, 3.4f)
                    }
                }

                SyntheticScenario.BENDING_OVER_PICKUP -> {
                    when (i) {
                        in 0..10 -> Triple(0.3f, 9.75f, 0.6f)
                        in 11..20 -> Triple(0.8f, 4.2f, 8.8f)
                        in 21..26 -> Triple(1.2f, 3.5f, 11.8f)
                        else -> Triple(0.4f, 9.6f, 1.5f)
                    }
                }

                SyntheticScenario.LYING_DOWN_INTENTIONALLY -> {
                    val progress = (i.toFloat() / totalSamples.toFloat()).coerceIn(0f, 1f)
                    val angle = progress * (Math.PI / 2.0)
                    val ayVal = (9.81 * cos(angle)).toFloat()
                    val azVal = (9.81 * sin(angle)).toFloat()
                    Triple(0.2f, ayVal, azVal)
                }

                SyntheticScenario.JUMPING_EXERCISE -> {
                    val cycle = i % 8
                    when (cycle) {
                        0, 1 -> Triple(1.0f, 3.2f, 0.8f)
                        2, 3 -> Triple(2.0f, 35.0f, 3.0f)
                        4, 5 -> Triple(1.5f, 15.0f, 2.0f)
                        else -> Triple(1.2f, 8.0f, 1.5f)
                    }
                }

                SyntheticScenario.SHAKING_PHONE -> {
                    if (i in 10..34) {
                        val sign = if (i % 2 == 0) 1f else -1f
                        Triple(22.0f * sign, 9.8f + 12.0f * sign, 8.5f * sign)
                    } else {
                        Triple(0.3f, 9.8f, 0.4f)
                    }
                }

                SyntheticScenario.SINGLE_NOISY_SPIKE -> {
                    if (i == 18) {
                        Triple(0.2f, 65.0f, 0.4f) // High peak alone without prior free-fall or subsequent stillness
                    } else {
                        Triple(0.2f, 9.81f, 0.3f)
                    }
                }

                SyntheticScenario.ORIENTATION_CHANGE_ONLY -> {
                    if (i < 20) {
                        Triple(0.1f, 9.81f, 0.2f)
                    } else {
                        Triple(9.81f, 0.2f, 0.1f)
                    }
                }
            }

            list.add(
                MotionSample(
                    timestampNanos = ts,
                    ax = ax,
                    ay = ay,
                    az = az,
                    sensorType = scenario.title,
                    isSynthetic = true,
                    gx = gx,
                    gy = gy,
                    gz = gz,
                    hasGyroscope = (gx != 0f || gy != 0f || gz != 0f)
                )
            )
        }
        return list
    }

    // ========================================================================
    // STEP 3: DEDICATED SYNTHETIC TEST TRACE GENERATORS
    // ========================================================================

    /**
     * 1) Phone resting flat on a surface (about 9.81 m/s² plus small sensor noise).
     * Must NOT trigger fall alert.
     */
    fun generatePhoneRestingFlat(
        durationMs: Long = 3000L,
        stepMs: Long = 20L
    ): List<MotionSample> {
        val count = (durationMs / stepMs).toInt()
        val list = ArrayList<MotionSample>(count)
        val stepNanos = stepMs * 1_000_000L

        for (i in 0 until count) {
            val noise = if (i % 2 == 0) 0.04f else -0.04f
            list.add(
                MotionSample(
                    timestampNanos = (i + 1) * stepNanos,
                    ax = 0.05f + noise,
                    ay = 0.05f,
                    az = 9.81f + noise,
                    sensorType = "Resting Flat",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * 2) Phone placed firmly on a table (contact spike about 15-25 m/s²), followed by table rest.
     * Must NOT trigger fall alert (no free-fall dip < 6.0 m/s², peak < 60.0 m/s²).
     */
    fun generatePlacingOnTable(
        durationMs: Long = 3500L,
        stepMs: Long = 20L
    ): List<MotionSample> {
        val count = (durationMs / stepMs).toInt()
        val list = ArrayList<MotionSample>(count)
        val stepNanos = stepMs * 1_000_000L

        for (i in 0 until count) {
            val ts = (i + 1) * stepNanos
            val (ax, ay, az) = when {
                i < 20 -> Triple(0.2f, 0.2f, 9.81f) // Held in hand
                i == 20 -> Triple(0.4f, 1.2f, 21.5f) // Contact spike ~21.5 m/s² (within 15-25 m/s²)
                i == 21 -> Triple(0.2f, 0.5f, 14.0f) // Contact dampening
                else -> {
                    val noise = if (i % 2 == 0) 0.02f else -0.02f
                    Triple(0.02f, 0.02f, 9.81f + noise) // Resting flat on table
                }
            }
            list.add(
                MotionSample(
                    timestampNanos = ts,
                    ax = ax,
                    ay = ay,
                    az = az,
                    sensorType = "Placing on Table",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * 3) Everyday motion: walking, sitting down hard, or running (peaks up to about 45 m/s²).
     * Must NOT trigger fall alert.
     */
    fun generateEverydayMotionActivities(
        stepMs: Long = 20L
    ): List<MotionSample> {
        val list = ArrayList<MotionSample>()
        val stepNanos = stepMs * 1_000_000L
        var currentSampleIndex = 0

        // Part A: Walking (1000 ms, 50 samples): oscillations 8.5 to 12.5 m/s²
        for (i in 0 until 50) {
            currentSampleIndex++
            val phase = i * 0.4
            val ay = (9.81 + 2.5 * sin(phase)).toFloat()
            list.add(
                MotionSample(
                    timestampNanos = currentSampleIndex * stepNanos,
                    ax = 1.0f * cos(phase).toFloat(),
                    ay = ay,
                    az = 0.8f,
                    sensorType = "Everyday Motion (Walking)",
                    isSynthetic = true
                )
            )
        }

        // Part B: Sitting down hard (800 ms, 40 samples): deceleration ~17.5 m/s²
        for (i in 0 until 40) {
            currentSampleIndex++
            val ay = if (i in 10..15) 17.5f else 9.81f
            list.add(
                MotionSample(
                    timestampNanos = currentSampleIndex * stepNanos,
                    ax = 0.5f,
                    ay = ay,
                    az = 1.0f,
                    sensorType = "Everyday Motion (Sitting Hard)",
                    isSynthetic = true
                )
            )
        }

        // Part C: Running (1600 ms, 80 samples): foot-strike peaks up to 45.0 m/s²
        for (i in 0 until 80) {
            currentSampleIndex++
            val cycle = i % 10
            val (ax, ay, az) = when (cycle) {
                0 -> Triple(3.0f, 44.5f, 4.0f) // Foot-strike peak ~44.8 m/s²
                1 -> Triple(2.0f, 25.0f, 2.5f)
                2, 3 -> Triple(0.5f, 7.5f, 1.0f)
                else -> Triple(1.0f, 10.5f, 1.2f)
            }
            list.add(
                MotionSample(
                    timestampNanos = currentSampleIndex * stepNanos,
                    ax = ax,
                    ay = ay,
                    az = az,
                    sensorType = "Everyday Motion (Running)",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * 4) Dip below 6.0 m/s² (80ms) → sub-threshold impact (~22.0 m/s²) within 1s → stillness 2.5s.
     * Must NOT trigger fall alert (impact 22.0 m/s² is below 30.0 m/s² and 27.0 m/s² thresholds).
     */
    fun generateDipImpact50Stillness(
        stepMs: Long = 20L
    ): List<MotionSample> {
        val list = ArrayList<MotionSample>()
        val stepNanos = stepMs * 1_000_000L
        var sampleIndex = 0

        // 0 to 500 ms (25 samples): Baseline rest at 9.81 m/s²
        for (i in 0 until 25) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.1f,
                    ay = 0.1f,
                    az = 9.81f,
                    sensorType = "Dip+ImpactSubThreshold+Still (Rest)",
                    isSynthetic = true
                )
            )
        }

        // 500 to 580 ms (4 samples = 80ms): Dip below 6.0 m/s² (magnitude ~1.8 m/s²)
        for (i in 0 until 4) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.3f,
                    ay = 1.0f,
                    az = 1.4f, // sqrt(0.09 + 1 + 1.96) = 1.74 m/s²
                    sensorType = "Dip+ImpactSubThreshold+Still (Free-Fall)",
                    isSynthetic = true
                )
            )
        }

        // 600 ms (sample): Sub-threshold impact peak of ~22.0 m/s² (below 30.0 and 27.0 thresholds)
        sampleIndex++
        list.add(
            MotionSample(
                timestampNanos = sampleIndex * stepNanos,
                ax = 6.0f,
                ay = 10.0f,
                az = 18.5f, // sqrt(36 + 100 + 342.25) = 21.87 m/s²
                sensorType = "Dip+ImpactSubThreshold+Still (Impact Sub-Threshold)",
                isSynthetic = true
            )
        )

        // 620 to 3200 ms (130 samples = 2600ms >= 2500ms): Stillness at 9.81 ± 0.1 m/s²
        for (i in 0 until 130) {
            sampleIndex++
            val noise = if (i % 2 == 0) 0.05f else -0.05f
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.1f,
                    ay = 0.1f,
                    az = 9.81f + noise,
                    sensorType = "Dip+ImpactSubThreshold+Still (Stillness)",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * 5) Dip below 6.0 m/s² (80ms) → impact of 65.0 m/s² within 1s → stillness 2.5s in 9.81 ± 2.0 m/s².
     * MUST TRIGGER candidate fall alert!
     */
    fun generateDipImpact65Stillness(
        stepMs: Long = 20L
    ): List<MotionSample> {
        val list = ArrayList<MotionSample>()
        val stepNanos = stepMs * 1_000_000L
        var sampleIndex = 0

        // 0 to 500 ms (25 samples): Baseline rest at 9.81 m/s²
        for (i in 0 until 25) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.1f,
                    ay = 0.1f,
                    az = 9.81f,
                    sensorType = "Dip+Impact65+Still (Rest)",
                    isSynthetic = true
                )
            )
        }

        // 500 to 580 ms (4 samples = 80ms): Dip below 6.0 m/s² (magnitude ~1.6 m/s² for 80ms >= 40ms)
        for (i in 0 until 4) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.2f,
                    ay = 1.0f,
                    az = 1.2f, // sqrt(0.04 + 1.0 + 1.44) = 1.57 m/s²
                    sensorType = "Dip+Impact65+Still (Free-Fall)",
                    isSynthetic = true
                )
            )
        }

        // 600 ms: Impact peak of 65.0 m/s² (> 60.0 m/s² threshold) within 1.0s
        sampleIndex++
        list.add(
            MotionSample(
                timestampNanos = sampleIndex * stepNanos,
                ax = 12.0f,
                ay = 25.0f,
                az = 58.8f, // sqrt(144 + 625 + 3457.44) = 65.0 m/s²
                sensorType = "Dip+Impact65+Still (Impact 65 m/s²)",
                isSynthetic = true
            )
        )

        // 620 to 3200 ms (130 samples = 2600ms >= 2500ms): Stillness in 9.81 ± 2.0 m/s² with low variance
        for (i in 0 until 130) {
            sampleIndex++
            val noise = if (i % 2 == 0) 0.05f else -0.05f
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.1f,
                    ay = 0.1f,
                    az = 9.81f + noise,
                    sensorType = "Dip+Impact65+Still (Stillness)",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * 6) Dip below 6.0 m/s² (80ms) and impact of 65.0 m/s², but then CONTINUOUS MOVEMENT.
     * Must NOT trigger fall alert (stillness phase fails).
     */
    fun generateDipImpact65ContinuousMovement(
        stepMs: Long = 20L
    ): List<MotionSample> {
        val list = ArrayList<MotionSample>()
        val stepNanos = stepMs * 1_000_000L
        var sampleIndex = 0

        // 0 to 500 ms: Baseline rest
        for (i in 0 until 25) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.1f,
                    ay = 0.1f,
                    az = 9.81f,
                    sensorType = "Dip+Impact65+Moving (Rest)",
                    isSynthetic = true
                )
            )
        }

        // Free-fall dip for 80ms
        for (i in 0 until 4) {
            sampleIndex++
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 0.2f,
                    ay = 1.0f,
                    az = 1.2f,
                    sensorType = "Dip+Impact65+Moving (Free-Fall)",
                    isSynthetic = true
                )
            )
        }

        // Impact of 65.0 m/s²
        sampleIndex++
        list.add(
            MotionSample(
                timestampNanos = sampleIndex * stepNanos,
                ax = 12.0f,
                ay = 25.0f,
                az = 58.8f,
                sensorType = "Dip+Impact65+Moving (Impact)",
                isSynthetic = true
            )
        )

        // Continuous active movement (130 samples = 2600ms): oscillations between 14.0 and 28.0 m/s²
        for (i in 0 until 130) {
            sampleIndex++
            val cycle = i % 8
            val ay = when (cycle) {
                0, 1 -> 24.0f
                2, 3 -> 18.0f
                4, 5 -> 6.0f
                else -> 14.0f
            }
            list.add(
                MotionSample(
                    timestampNanos = sampleIndex * stepNanos,
                    ax = 2.0f,
                    ay = ay,
                    az = 4.0f,
                    sensorType = "Dip+Impact65+Moving (Continuous Movement)",
                    isSynthetic = true
                )
            )
        }
        return list
    }

    /**
     * Debug-only synthetic fall sequence for device testing.
     * Gated by [BuildConfig.DEBUG] so it cannot be run or leak into release builds.
     */
    fun generateDebugFallSequence(): List<MotionSample> {
        if (!BuildConfig.DEBUG) {
            return emptyList()
        }
        return generateDipImpact65Stillness()
    }
}
