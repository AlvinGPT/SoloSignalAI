package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.FeedbackLearningLabel
import com.example.domain.IncidentVerificationType
import com.example.domain.MedicalIdProfile
import com.example.domain.MovementCategory
import com.example.domain.PersonalLearningEventStatus
import com.example.domain.PersonalLearningExample
import com.example.domain.PersonalLearningJsonCodec
import com.example.domain.PersonalLearningStage
import com.example.domain.PersonalMovementProfile
import com.example.domain.PostFallJsonCodec
import com.example.domain.SosAlertOutcomeState
import com.example.domain.SosLearningSourceType
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "safety_events")
data class SafetyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val eventGroupUuid: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: String,
    val title: String,
    val outcomeSummary: String,
    val deliveryStatus: String,
    val sensorDetails: String = "",
    val isSyntheticTest: Boolean = false,
    val modelVersion: String = "",
    val predictedActivity: String = "",
    val modelConfidence: Float? = null,
    val isUncertain: Boolean = false,
    val explainabilityJson: String = ""
) {
    constructor(
        timestampEpochMillis: Long,
        eventType: String,
        title: String,
        details: String,
        channelUsed: String,
        deliveryOutcome: String,
        modelVersion: String = "",
        predictedActivity: String = "",
        modelConfidence: Float? = null,
        isUncertain: Boolean = false,
        explainabilityJson: String = ""
    ) : this(
        id = 0L,
        eventGroupUuid = UUID.randomUUID().toString(),
        timestampMillis = timestampEpochMillis,
        eventType = eventType,
        title = title,
        outcomeSummary = details,
        deliveryStatus = deliveryOutcome.ifBlank { channelUsed },
        sensorDetails = if (deliveryOutcome.isNotBlank() && channelUsed.isNotBlank()) "Channel: $channelUsed" else "",
        isSyntheticTest = eventType == "TEST_ALERT_TRIGGERED",
        modelVersion = modelVersion,
        predictedActivity = predictedActivity,
        modelConfidence = modelConfidence,
        isUncertain = isUncertain,
        explainabilityJson = explainabilityJson
    )
}

typealias EventLogEntity = SafetyEventEntity

@Entity(tableName = "post_fall_assessments")
data class PostFallAssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val linkedEventId: String,
    val timestampMillis: Long,
    val completionStatus: String,
    val incidentVerification: String,
    val isFalseDetection: Boolean,
    val isPhoneDrop: Boolean,
    val painLevel: String,
    val difficultyBreathing: String,
    val dizzyOrWeak: String,
    val confusionOrDifficultyResponding: String,
    val hitHead: String,
    val needMedicalAssistance: String,
    val injuryLocationsJson: String = "[]",
    val recoveryAbility: String = "SKIPPED",
    val bleedingReported: Boolean = false,
    val userNotes: String = "",
    val severeSymptomDetected: Boolean = false,
    val autoSosTriggered: Boolean = false,
    val triggeredAlertId: String? = null,
    val riskTier: String = "SELF_MONITORING",
    val advisoryHeadline: String = "",
    val advisoryExplanation: String = "",
    val crossCheckHighlightsJson: String = "[]",
    val recommendedNextStepsJson: String = "[]",
    val usedMedicalIdContext: Boolean = false,
    val evaluationSource: String = "ON_DEVICE_CLINICAL_RULES"
)

@Entity(tableName = "medical_id_profiles")
data class MedicalIdEntity(
    @PrimaryKey val profileId: String,
    val fullName: String = "",
    val dateOfBirth: String = "",
    val ageOrGroup: String = "",
    val bloodType: String = "",
    val knownConditionsJson: String = "[]",
    val medicationsJson: String = "[]",
    val allergiesJson: String = "[]",
    val mobilityOrFallHistory: String = "",
    val emergencyNotes: String = "",
    val allowAiCrossCheck: Boolean = true,
    val includeInEmergencyAlerts: Boolean = false,
    val syncToCloudFirestore: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun toDomain(): MedicalIdProfile {
        return MedicalIdProfile(
            profileId = profileId,
            fullName = fullName,
            dateOfBirth = dateOfBirth,
            ageOrGroup = ageOrGroup,
            bloodType = bloodType,
            knownConditions = PostFallJsonCodec.decodeStringList(knownConditionsJson),
            medications = PostFallJsonCodec.decodeStringList(medicationsJson),
            allergies = PostFallJsonCodec.decodeStringList(allergiesJson),
            mobilityOrFallHistory = mobilityOrFallHistory,
            emergencyNotes = emergencyNotes,
            allowAiCrossCheck = allowAiCrossCheck,
            includeInEmergencyAlerts = includeInEmergencyAlerts,
            syncToCloudFirestore = syncToCloudFirestore,
            updatedAtEpochMillis = updatedAtMillis
        )
    }

    companion object {
        fun fromDomain(profile: MedicalIdProfile): MedicalIdEntity {
            return MedicalIdEntity(
                profileId = profile.profileId.ifBlank { "local_default" },
                fullName = profile.fullName.trim(),
                dateOfBirth = profile.dateOfBirth.trim(),
                ageOrGroup = profile.ageOrGroup.trim(),
                bloodType = profile.bloodType.trim(),
                knownConditionsJson = PostFallJsonCodec.encodeStringList(profile.knownConditions),
                medicationsJson = PostFallJsonCodec.encodeStringList(profile.medications),
                allergiesJson = PostFallJsonCodec.encodeStringList(profile.allergies),
                mobilityOrFallHistory = profile.mobilityOrFallHistory.trim(),
                emergencyNotes = profile.emergencyNotes.trim(),
                allowAiCrossCheck = profile.allowAiCrossCheck,
                includeInEmergencyAlerts = profile.includeInEmergencyAlerts,
                syncToCloudFirestore = profile.syncToCloudFirestore,
                updatedAtMillis = if (profile.updatedAtEpochMillis > 0L) {
                    profile.updatedAtEpochMillis
                } else {
                    System.currentTimeMillis()
                }
            )
        }
    }
}

@Entity(tableName = "personal_learning_examples")
data class PersonalLearningExampleEntity(
    @PrimaryKey val exampleId: String,
    val userId: String = "local_default",
    val linkedEventId: String = "",
    val timestampMillis: Long = System.currentTimeMillis(),
    val incidentVerification: String = "NOT_SURE",
    val learningLabel: String = "UNCERTAIN_EXCLUDED",
    val featureVectorJson: String = "[]",
    val peakTotalAccelG: Float = 1.0f,
    val minTotalAccelG: Float = 1.0f,
    val postImpactStillnessStdDevMs2: Float = 0f,
    val peakGyroDegPerSec: Float = 0f,
    val baseModelPredictedCategory: String = "UNKNOWN_UNCERTAIN",
    val baseModelConfidence: Float? = null,
    val includedInTraining: Boolean = false,
    val validationNote: String = "",
    val alertId: String = "",
    val sourceType: String = "QUESTIONNAIRE_ONLY",
    val eventStatus: String = "EXCLUDED_FROM_FALL_LEARNING",
    val detectionMethod: String = "HYBRID_CNN_PLUS_PHYSICS",
    val physicsFallbackInvolved: Boolean = false,
    val alertOutcome: String = "NONE",
    val recipientAcknowledged: Boolean = false,
    val acknowledgedAtMillis: Long? = null,
    val acknowledgedByName: String = "",
    val questionnaireCompleted: Boolean = true,
    val questionnaireCompletedAtMillis: Long? = null
) {
    fun toDomain(): PersonalLearningExample {
        val parsedLabel = FeedbackLearningLabel.fromStorageKey(learningLabel)
        val parsedSource = SosLearningSourceType.fromStorageKey(sourceType)
        val parsedStatus = if (eventStatus.isNotBlank()) {
            PersonalLearningEventStatus.fromStorageKey(eventStatus)
        } else {
            when {
                parsedLabel == FeedbackLearningLabel.UNVERIFIED_SOS_EVENT ->
                    PersonalLearningEventStatus.AWAITING_FEEDBACK
                parsedLabel == FeedbackLearningLabel.MANUAL_SOS_EVENT ||
                    parsedSource == SosLearningSourceType.MANUAL_SOS ->
                    PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL
                includedInTraining && parsedLabel.isConfirmedTrainingExample ->
                    PersonalLearningEventStatus.CONFIRMED_MOVEMENT_PATTERN
                else ->
                    PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING
            }
        }
        return PersonalLearningExample(
            exampleId = exampleId,
            userId = userId,
            linkedEventId = linkedEventId,
            timestampEpochMillis = timestampMillis,
            incidentVerification = IncidentVerificationType.fromStorageKey(incidentVerification)
                ?: IncidentVerificationType.NOT_SURE,
            learningLabel = parsedLabel,
            featureVector16D = PersonalLearningJsonCodec.decodeFloatList(featureVectorJson),
            peakTotalAccelG = peakTotalAccelG,
            minTotalAccelG = minTotalAccelG,
            postImpactStillnessStdDevMs2 = postImpactStillnessStdDevMs2,
            peakGyroDegPerSec = peakGyroDegPerSec,
            baseModelPredictedCategory = MovementCategory.fromStorageName(baseModelPredictedCategory),
            baseModelConfidence = baseModelConfidence,
            includedInTraining = includedInTraining,
            validationNote = validationNote,
            alertId = alertId,
            sourceType = parsedSource,
            eventStatus = parsedStatus,
            detectionMethod = detectionMethod,
            physicsFallbackInvolved = physicsFallbackInvolved,
            alertOutcome = SosAlertOutcomeState.fromStorageKey(alertOutcome),
            recipientAcknowledged = recipientAcknowledged,
            acknowledgedAtEpochMillis = acknowledgedAtMillis,
            acknowledgedByName = acknowledgedByName,
            questionnaireCompleted = questionnaireCompleted,
            questionnaireCompletedAtEpochMillis = questionnaireCompletedAtMillis
        )
    }

    companion object {
        fun fromDomain(example: PersonalLearningExample): PersonalLearningExampleEntity {
            return PersonalLearningExampleEntity(
                exampleId = example.exampleId,
                userId = example.userId.ifBlank { "local_default" },
                linkedEventId = example.linkedEventId,
                timestampMillis = example.timestampEpochMillis,
                incidentVerification = example.incidentVerification.name,
                learningLabel = example.learningLabel.name,
                featureVectorJson = PersonalLearningJsonCodec.encodeFloatList(example.featureVector16D),
                peakTotalAccelG = example.peakTotalAccelG,
                minTotalAccelG = example.minTotalAccelG,
                postImpactStillnessStdDevMs2 = example.postImpactStillnessStdDevMs2,
                peakGyroDegPerSec = example.peakGyroDegPerSec,
                baseModelPredictedCategory = example.baseModelPredictedCategory.name,
                baseModelConfidence = example.baseModelConfidence,
                includedInTraining = example.includedInTraining,
                validationNote = example.validationNote,
                alertId = example.alertId.trim(),
                sourceType = example.sourceType.name,
                eventStatus = example.eventStatus.name,
                detectionMethod = example.detectionMethod,
                physicsFallbackInvolved = example.physicsFallbackInvolved,
                alertOutcome = example.alertOutcome.name,
                recipientAcknowledged = example.recipientAcknowledged,
                acknowledgedAtMillis = example.acknowledgedAtEpochMillis,
                acknowledgedByName = example.acknowledgedByName,
                questionnaireCompleted = example.questionnaireCompleted,
                questionnaireCompletedAtMillis = example.questionnaireCompletedAtEpochMillis
            )
        }
    }
}

@Entity(tableName = "personal_movement_profiles")
data class PersonalMovementProfileEntity(
    @PrimaryKey val userId: String,
    val learningStage: String = PersonalLearningStage.GETTING_STARTED.name,
    val confirmedNormalCount: Int = 0,
    val confirmedFallCount: Int = 0,
    val excludedUncertainCount: Int = 0,
    val excludedContradictoryCount: Int = 0,
    val phoneDropExampleCount: Int = 0,
    val sittingExampleCount: Int = 0,
    val walkingNormalExampleCount: Int = 0,
    val falseDetectionExampleCount: Int = 0,
    val normalPrototypeCentroidJson: String = "[]",
    val fallPrototypeCentroidJson: String = "[]",
    val calibratedConfidenceDelta: Float = 0f,
    val calibratedImpactThresholdDeltaMs2: Float = 0f,
    val personalisationEnabled: Boolean = true,
    val suppressedFalseAlarmsCount: Int = 0,
    val lastUpdatedMillis: Long = 0L,
    val profileSummaryNote: String = "Using general pre-trained model baseline.",
    val awaitingFeedbackSosCount: Int = 0,
    val manualSosEventCount: Int = 0,
    val acknowledgedAlertCount: Int = 0,
    val validatedSosEventCount: Int = 0
) {
    fun toDomain(): PersonalMovementProfile {
        return PersonalMovementProfile(
            userId = userId,
            learningStage = PersonalLearningStage.fromStorageKey(learningStage),
            confirmedNormalCount = confirmedNormalCount,
            confirmedFallCount = confirmedFallCount,
            excludedUncertainCount = excludedUncertainCount,
            excludedContradictoryCount = excludedContradictoryCount,
            phoneDropExampleCount = phoneDropExampleCount,
            sittingExampleCount = sittingExampleCount,
            walkingNormalExampleCount = walkingNormalExampleCount,
            falseDetectionExampleCount = falseDetectionExampleCount,
            normalPrototypeCentroid16D = PersonalLearningJsonCodec.decodeFloatList(normalPrototypeCentroidJson),
            fallPrototypeCentroid16D = PersonalLearningJsonCodec.decodeFloatList(fallPrototypeCentroidJson),
            calibratedConfidenceDelta = calibratedConfidenceDelta,
            calibratedImpactThresholdDeltaMs2 = calibratedImpactThresholdDeltaMs2,
            personalisationEnabled = personalisationEnabled,
            suppressedFalseAlarmsCount = suppressedFalseAlarmsCount,
            lastUpdatedEpochMillis = lastUpdatedMillis,
            profileSummaryNote = profileSummaryNote,
            awaitingFeedbackSosCount = awaitingFeedbackSosCount,
            manualSosEventCount = manualSosEventCount,
            acknowledgedAlertCount = acknowledgedAlertCount,
            validatedSosEventCount = validatedSosEventCount
        )
    }

    companion object {
        fun fromDomain(profile: PersonalMovementProfile): PersonalMovementProfileEntity {
            return PersonalMovementProfileEntity(
                userId = profile.userId.ifBlank { "local_default" },
                learningStage = profile.learningStage.name,
                confirmedNormalCount = profile.confirmedNormalCount,
                confirmedFallCount = profile.confirmedFallCount,
                excludedUncertainCount = profile.excludedUncertainCount,
                excludedContradictoryCount = profile.excludedContradictoryCount,
                phoneDropExampleCount = profile.phoneDropExampleCount,
                sittingExampleCount = profile.sittingExampleCount,
                walkingNormalExampleCount = profile.walkingNormalExampleCount,
                falseDetectionExampleCount = profile.falseDetectionExampleCount,
                normalPrototypeCentroidJson = PersonalLearningJsonCodec.encodeFloatList(profile.normalPrototypeCentroid16D),
                fallPrototypeCentroidJson = PersonalLearningJsonCodec.encodeFloatList(profile.fallPrototypeCentroid16D),
                calibratedConfidenceDelta = profile.calibratedConfidenceDelta,
                calibratedImpactThresholdDeltaMs2 = profile.calibratedImpactThresholdDeltaMs2,
                personalisationEnabled = profile.personalisationEnabled,
                suppressedFalseAlarmsCount = profile.suppressedFalseAlarmsCount,
                lastUpdatedMillis = profile.lastUpdatedEpochMillis,
                profileSummaryNote = profile.profileSummaryNote,
                awaitingFeedbackSosCount = profile.awaitingFeedbackSosCount,
                manualSosEventCount = profile.manualSosEventCount,
                acknowledgedAlertCount = profile.acknowledgedAlertCount,
                validatedSosEventCount = profile.validatedSosEventCount
            )
        }
    }
}

@Dao
interface SafetyEventDao {
    @Query("SELECT * FROM safety_events ORDER BY timestampMillis DESC")
    fun observeAllEvents(): Flow<List<SafetyEventEntity>>

    @Query("SELECT * FROM safety_events ORDER BY timestampMillis DESC LIMIT :limit")
    fun getRecentEventsSnapshot(limit: Int = 20): List<SafetyEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SafetyEventEntity): Long

    @Query("DELETE FROM safety_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("DELETE FROM safety_events")
    suspend fun clearAllEvents()

    @Query("SELECT * FROM post_fall_assessments ORDER BY timestampMillis DESC")
    fun observePostFallAssessments(): Flow<List<PostFallAssessmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPostFallAssessment(assessment: PostFallAssessmentEntity)

    @Query("DELETE FROM post_fall_assessments")
    suspend fun clearAllPostFallAssessments()

    @Query("SELECT * FROM medical_id_profiles WHERE profileId = :profileId LIMIT 1")
    fun observeMedicalIdProfile(profileId: String): Flow<MedicalIdEntity?>

    @Query("SELECT * FROM medical_id_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getMedicalIdProfileSnapshot(profileId: String): MedicalIdEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedicalIdProfile(profile: MedicalIdEntity)

    @Query("DELETE FROM medical_id_profiles WHERE profileId = :profileId")
    suspend fun deleteMedicalIdProfile(profileId: String)

    @Query("DELETE FROM medical_id_profiles")
    suspend fun clearAllMedicalIdProfiles()

    // Personalised Fall Detection & Continuous Learning Queries (Strictly Local On-Device)
    @Query("SELECT * FROM personal_movement_profiles WHERE userId = :userId LIMIT 1")
    fun observePersonalMovementProfile(userId: String): Flow<PersonalMovementProfileEntity?>

    @Query("SELECT * FROM personal_movement_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getPersonalMovementProfileSnapshot(userId: String): PersonalMovementProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersonalMovementProfile(profile: PersonalMovementProfileEntity)

    @Query("DELETE FROM personal_movement_profiles WHERE userId = :userId")
    suspend fun deletePersonalMovementProfileForUser(userId: String)

    @Query("DELETE FROM personal_movement_profiles")
    suspend fun clearAllPersonalMovementProfiles()

    @Query("SELECT * FROM personal_learning_examples WHERE userId = :userId ORDER BY timestampMillis DESC")
    fun observePersonalLearningExamples(userId: String): Flow<List<PersonalLearningExampleEntity>>

    @Query("SELECT * FROM personal_learning_examples WHERE userId = :userId ORDER BY timestampMillis DESC")
    suspend fun getPersonalLearningExamplesSnapshot(userId: String): List<PersonalLearningExampleEntity>

    @Query("SELECT * FROM personal_learning_examples WHERE userId = :userId AND alertId = :alertId LIMIT 1")
    suspend fun getPersonalLearningExampleByAlertId(userId: String, alertId: String): PersonalLearningExampleEntity?

    @Query("SELECT * FROM personal_learning_examples WHERE userId = :userId AND (linkedEventId = :linkedEventId OR alertId = :linkedEventId) ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun getPersonalLearningExampleByLinkedEventId(userId: String, linkedEventId: String): PersonalLearningExampleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersonalLearningExample(example: PersonalLearningExampleEntity)

    @Query("DELETE FROM personal_learning_examples WHERE userId = :userId")
    suspend fun deletePersonalLearningExamplesForUser(userId: String)

    @Query("DELETE FROM personal_learning_examples")
    suspend fun clearAllPersonalLearningExamples()
}

@Database(
    entities = [
        SafetyEventEntity::class,
        PostFallAssessmentEntity::class,
        MedicalIdEntity::class,
        PersonalLearningExampleEntity::class,
        PersonalMovementProfileEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class EventHistoryDatabase : RoomDatabase() {
    abstract fun safetyEventDao(): SafetyEventDao

    fun eventLogDao(): SafetyEventDao = safetyEventDao()

    companion object {
        /**
         * Explicit non-destructive SQLite migration from version 1 to version 2.
         * Preserves 100% of existing safety_events rows while adding ML explainability columns.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE safety_events ADD COLUMN modelVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE safety_events ADD COLUMN predictedActivity TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE safety_events ADD COLUMN modelConfidence REAL")
                db.execSQL("ALTER TABLE safety_events ADD COLUMN isUncertain INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE safety_events ADD COLUMN explainabilityJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Explicit non-destructive SQLite migration from version 2 to version 3.
         * Preserves 100% of existing safety_events rows while adding post_fall_assessments and medical_id_profiles tables.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `post_fall_assessments` (
                        `assessmentId` TEXT NOT NULL,
                        `linkedEventId` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `completionStatus` TEXT NOT NULL,
                        `incidentVerification` TEXT NOT NULL,
                        `isFalseDetection` INTEGER NOT NULL,
                        `isPhoneDrop` INTEGER NOT NULL,
                        `painLevel` TEXT NOT NULL,
                        `difficultyBreathing` TEXT NOT NULL,
                        `dizzyOrWeak` TEXT NOT NULL,
                        `confusionOrDifficultyResponding` TEXT NOT NULL,
                        `hitHead` TEXT NOT NULL,
                        `needMedicalAssistance` TEXT NOT NULL,
                        `injuryLocationsJson` TEXT NOT NULL DEFAULT '[]',
                        `recoveryAbility` TEXT NOT NULL DEFAULT 'SKIPPED',
                        `bleedingReported` INTEGER NOT NULL DEFAULT 0,
                        `userNotes` TEXT NOT NULL DEFAULT '',
                        `severeSymptomDetected` INTEGER NOT NULL DEFAULT 0,
                        `autoSosTriggered` INTEGER NOT NULL DEFAULT 0,
                        `triggeredAlertId` TEXT,
                        `riskTier` TEXT NOT NULL DEFAULT 'SELF_MONITORING',
                        `advisoryHeadline` TEXT NOT NULL DEFAULT '',
                        `advisoryExplanation` TEXT NOT NULL DEFAULT '',
                        `crossCheckHighlightsJson` TEXT NOT NULL DEFAULT '[]',
                        `recommendedNextStepsJson` TEXT NOT NULL DEFAULT '[]',
                        `usedMedicalIdContext` INTEGER NOT NULL DEFAULT 0,
                        `evaluationSource` TEXT NOT NULL DEFAULT 'ON_DEVICE_CLINICAL_RULES',
                        PRIMARY KEY(`assessmentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medical_id_profiles` (
                        `profileId` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL DEFAULT '',
                        `dateOfBirth` TEXT NOT NULL DEFAULT '',
                        `ageOrGroup` TEXT NOT NULL DEFAULT '',
                        `bloodType` TEXT NOT NULL DEFAULT '',
                        `knownConditionsJson` TEXT NOT NULL DEFAULT '[]',
                        `medicationsJson` TEXT NOT NULL DEFAULT '[]',
                        `allergiesJson` TEXT NOT NULL DEFAULT '[]',
                        `mobilityOrFallHistory` TEXT NOT NULL DEFAULT '',
                        `emergencyNotes` TEXT NOT NULL DEFAULT '',
                        `allowAiCrossCheck` INTEGER NOT NULL DEFAULT 1,
                        `includeInEmergencyAlerts` INTEGER NOT NULL DEFAULT 0,
                        `syncToCloudFirestore` INTEGER NOT NULL DEFAULT 1,
                        `updatedAtMillis` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`profileId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Explicit non-destructive SQLite migration from version 3 to version 4.
         * Preserves 100% of existing safety_events, post_fall_assessments, and medical_id_profiles rows
         * while adding on-device personal_learning_examples and personal_movement_profiles tables.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_learning_examples` (
                        `exampleId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL DEFAULT 'local_default',
                        `linkedEventId` TEXT NOT NULL DEFAULT '',
                        `timestampMillis` INTEGER NOT NULL DEFAULT 0,
                        `incidentVerification` TEXT NOT NULL DEFAULT 'NOT_SURE',
                        `learningLabel` TEXT NOT NULL DEFAULT 'UNCERTAIN_EXCLUDED',
                        `featureVectorJson` TEXT NOT NULL DEFAULT '[]',
                        `peakTotalAccelG` REAL NOT NULL DEFAULT 1.0,
                        `minTotalAccelG` REAL NOT NULL DEFAULT 1.0,
                        `postImpactStillnessStdDevMs2` REAL NOT NULL DEFAULT 0.0,
                        `peakGyroDegPerSec` REAL NOT NULL DEFAULT 0.0,
                        `baseModelPredictedCategory` TEXT NOT NULL DEFAULT 'UNKNOWN_UNCERTAIN',
                        `baseModelConfidence` REAL,
                        `includedInTraining` INTEGER NOT NULL DEFAULT 0,
                        `validationNote` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`exampleId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_movement_profiles` (
                        `userId` TEXT NOT NULL,
                        `learningStage` TEXT NOT NULL DEFAULT 'GETTING_STARTED',
                        `confirmedNormalCount` INTEGER NOT NULL DEFAULT 0,
                        `confirmedFallCount` INTEGER NOT NULL DEFAULT 0,
                        `excludedUncertainCount` INTEGER NOT NULL DEFAULT 0,
                        `excludedContradictoryCount` INTEGER NOT NULL DEFAULT 0,
                        `phoneDropExampleCount` INTEGER NOT NULL DEFAULT 0,
                        `sittingExampleCount` INTEGER NOT NULL DEFAULT 0,
                        `walkingNormalExampleCount` INTEGER NOT NULL DEFAULT 0,
                        `falseDetectionExampleCount` INTEGER NOT NULL DEFAULT 0,
                        `normalPrototypeCentroidJson` TEXT NOT NULL DEFAULT '[]',
                        `fallPrototypeCentroidJson` TEXT NOT NULL DEFAULT '[]',
                        `calibratedConfidenceDelta` REAL NOT NULL DEFAULT 0.0,
                        `calibratedImpactThresholdDeltaMs2` REAL NOT NULL DEFAULT 0.0,
                        `personalisationEnabled` INTEGER NOT NULL DEFAULT 1,
                        `suppressedFalseAlarmsCount` INTEGER NOT NULL DEFAULT 0,
                        `lastUpdatedMillis` INTEGER NOT NULL DEFAULT 0,
                        `profileSummaryNote` TEXT NOT NULL DEFAULT 'Using general pre-trained model baseline.',
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Explicit non-destructive SQLite migration from version 4 to version 5.
         * Preserves 100% of existing personal_learning_examples and personal_movement_profiles rows
         * while adding SOS event learning columns (alertId, sourceType, eventStatus, detectionMethod,
         * physicsFallbackInvolved, alertOutcome, recipientAcknowledged, acknowledgedAtMillis,
         * acknowledgedByName, questionnaireCompleted, questionnaireCompletedAtMillis) and SOS summary counters.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN alertId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'QUESTIONNAIRE_ONLY'")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN eventStatus TEXT NOT NULL DEFAULT 'CONFIRMED_MOVEMENT_PATTERN'")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN detectionMethod TEXT NOT NULL DEFAULT 'HYBRID_CNN_PLUS_PHYSICS'")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN physicsFallbackInvolved INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN alertOutcome TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN recipientAcknowledged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN acknowledgedAtMillis INTEGER")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN acknowledgedByName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN questionnaireCompleted INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE personal_learning_examples ADD COLUMN questionnaireCompletedAtMillis INTEGER")

                db.execSQL("ALTER TABLE personal_movement_profiles ADD COLUMN awaitingFeedbackSosCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE personal_movement_profiles ADD COLUMN manualSosEventCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE personal_movement_profiles ADD COLUMN acknowledgedAlertCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE personal_movement_profiles ADD COLUMN validatedSosEventCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: EventHistoryDatabase? = null

        fun getInstance(context: Context): EventHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EventHistoryDatabase::class.java,
                    "solosignal_event_history.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
