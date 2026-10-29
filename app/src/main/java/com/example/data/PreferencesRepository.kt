package com.example.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.AlertChannelType
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GoogleChatConnectionStatus
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactVerificationStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "solosignal_preferences")

data class UserPreferencesState(
    val onboardingCompleted: Boolean = false,
    val countdownSeconds: Int = 15,
    val vibrationEnabled: Boolean = true,
    val sosAudioEnabled: Boolean = false,
    val sosAudioConsentGranted: Boolean = false,
    val sosAudioRetentionDays: Int = 7,
    val postTriggerAudioSeconds: Int = 3,
    val gmailAccessToken: String = "",
    val deviceInstallationId: String = "",
    val fcmToken: String = "",
    val trustedContact: TrustedContact = TrustedContact(),
    val googleChatConfig: GoogleChatAlertConfig = GoogleChatAlertConfig()
)

class PreferencesRepository(private val context: Context) {

    private object GlobalKeys {
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds_v2")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val DEVICE_INSTALLATION_ID = stringPreferencesKey("device_installation_id")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")

        // Legacy unscoped keys (used ONLY for safe one-time migration when linkId matches the authenticated uid)
        val LEGACY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LEGACY_USER_DISPLAY_NAME = stringPreferencesKey("user_display_name")
        val LEGACY_CONTACT_NAME = stringPreferencesKey("contact_name")
        val LEGACY_CONTACT_EMAIL = stringPreferencesKey("contact_email")
        val LEGACY_CONTACT_PHONE = stringPreferencesKey("contact_phone")
        val LEGACY_CONTACT_RELATIONSHIP = stringPreferencesKey("contact_relationship")
        val LEGACY_PREFERRED_CHANNEL = stringPreferencesKey("preferred_channel")
        val LEGACY_CONSENT_GRANTED = booleanPreferencesKey("consent_granted")
        val LEGACY_OPTIONAL_LOCATION_ENABLED = booleanPreferencesKey("optional_location_enabled")
        val LEGACY_PHONE_POPUP_ALERTS_ENABLED = booleanPreferencesKey("phone_popup_alerts_enabled")
        val LEGACY_CONTACT_VERIFICATION_STATUS = stringPreferencesKey("contact_verification_status")
        val LEGACY_CONTACT_VERIFICATION_DETAIL = stringPreferencesKey("contact_verification_detail")
        val LEGACY_CONTACT_HAS_REGISTERED_ACCOUNT = booleanPreferencesKey("contact_has_registered_account")
        val LEGACY_CONTACT_ACCEPTED = booleanPreferencesKey("contact_accepted")
        val LEGACY_CONTACT_REGISTERED_DEVICE_COUNT = intPreferencesKey("contact_registered_device_count")
        val LEGACY_CONTACT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("contact_notifications_enabled")
        val LEGACY_CONTACT_LINK_ID = stringPreferencesKey("contact_link_id")
        val LEGACY_CONTACT_RECIPIENT_UID = stringPreferencesKey("contact_recipient_uid")
    }

    private data class UserScopedKeys(val scopePrefix: String) {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("${scopePrefix}_onboarding_completed")
        val GMAIL_ACCESS_TOKEN = stringPreferencesKey("${scopePrefix}_gmail_access_token")

        val USER_DISPLAY_NAME = stringPreferencesKey("${scopePrefix}_user_display_name")
        val CONTACT_NAME = stringPreferencesKey("${scopePrefix}_contact_name")
        val CONTACT_EMAIL = stringPreferencesKey("${scopePrefix}_contact_email")
        val CONTACT_PHONE = stringPreferencesKey("${scopePrefix}_contact_phone")
        val CONTACT_RELATIONSHIP = stringPreferencesKey("${scopePrefix}_contact_relationship")
        val PREFERRED_CHANNEL = stringPreferencesKey("${scopePrefix}_preferred_channel")
        val CONSENT_GRANTED = booleanPreferencesKey("${scopePrefix}_consent_granted")
        val OPTIONAL_LOCATION_ENABLED = booleanPreferencesKey("${scopePrefix}_optional_location_enabled")
        val PHONE_POPUP_ALERTS_ENABLED = booleanPreferencesKey("${scopePrefix}_phone_popup_alerts_enabled")
        val SOS_AUDIO_ENABLED = booleanPreferencesKey("${scopePrefix}_sos_audio_enabled")
        val SOS_AUDIO_CONSENT_GRANTED = booleanPreferencesKey("${scopePrefix}_sos_audio_consent_granted")
        val SOS_AUDIO_RETENTION_DAYS = intPreferencesKey("${scopePrefix}_sos_audio_retention_days")
        val POST_TRIGGER_AUDIO_SECONDS = intPreferencesKey("${scopePrefix}_post_trigger_audio_seconds")

        val CONTACT_VERIFICATION_STATUS = stringPreferencesKey("${scopePrefix}_contact_verification_status")
        val CONTACT_VERIFICATION_DETAIL = stringPreferencesKey("${scopePrefix}_contact_verification_detail")
        val CONTACT_HAS_REGISTERED_ACCOUNT = booleanPreferencesKey("${scopePrefix}_contact_has_registered_account")
        val CONTACT_ACCEPTED = booleanPreferencesKey("${scopePrefix}_contact_accepted")
        val CONTACT_REGISTERED_DEVICE_COUNT = intPreferencesKey("${scopePrefix}_contact_registered_device_count")
        val CONTACT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("${scopePrefix}_contact_notifications_enabled")
        val CONTACT_LINK_ID = stringPreferencesKey("${scopePrefix}_contact_link_id")
        val CONTACT_RECIPIENT_UID = stringPreferencesKey("${scopePrefix}_contact_recipient_uid")

        val CHAT_ENABLED = booleanPreferencesKey("${scopePrefix}_chat_enabled")
        val CHAT_BACKEND_URL = stringPreferencesKey("${scopePrefix}_chat_backend_url")
        val CHAT_SPACE_NAME = stringPreferencesKey("${scopePrefix}_chat_space_name")
        val CHAT_SPACE_LABEL = stringPreferencesKey("${scopePrefix}_chat_space_label")
        val CHAT_CONNECTION_STATUS = stringPreferencesKey("${scopePrefix}_chat_connection_status")
        val CHAT_STATUS_DETAIL = stringPreferencesKey("${scopePrefix}_chat_status_detail")
        val CHAT_INITIAL_MESSAGE_SENT = booleanPreferencesKey("${scopePrefix}_chat_initial_message_sent")
        val CHAT_CONNECTED_DESTINATION_KEY = stringPreferencesKey("${scopePrefix}_chat_connected_destination_key")
        val CHAT_LAST_VERIFIED_MILLIS = longPreferencesKey("${scopePrefix}_chat_last_verified_millis")
    }

    private val sharedPrefs = context.applicationContext.getSharedPreferences(
        "solosignal_scoped_user_prefs",
        Context.MODE_PRIVATE
    )
    private val _activeUserId = MutableStateFlow<String?>(resolveCurrentFirebaseUid())
    val activeUserId: StateFlow<String?> = _activeUserId.asStateFlow()
    private val _prefsUpdateTick = MutableStateFlow(0L)

    private fun resolveCurrentFirebaseUid(): String? {
        return try {
            Firebase.auth.currentUser?.uid?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeUid(uid: String?): String {
        val raw = uid?.trim().orEmpty()
        if (raw.isBlank()) return LOCAL_UNAUTHENTICATED_SCOPE
        return "u_${raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(96)}"
    }

    private fun scopedKeysFor(uidOverride: String? = null): UserScopedKeys {
        val effectiveUid = uidOverride?.takeIf { it.isNotBlank() }
            ?: _activeUserId.value?.takeIf { it.isNotBlank() }
            ?: resolveCurrentFirebaseUid()
        return UserScopedKeys(sanitizeUid(effectiveUid))
    }

    private fun notifyPrefsChanged() {
        _prefsUpdateTick.value = _prefsUpdateTick.value + 1L
    }

    private suspend fun safeDataStoreEdit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(250L) {
                context.dataStore.edit { prefs -> block(prefs) }
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun safeDataStoreSnapshot(): Preferences? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(250L) {
                context.dataStore.data.first()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Switches the active user namespace so each authenticated user's contacts and settings
     * remain strictly private and isolated to their own account.
     */
    fun setActiveUser(uid: String?) {
        _activeUserId.value = uid?.trim()?.takeIf { it.isNotBlank() }
        notifyPrefsChanged()
    }

    val preferencesFlow: Flow<UserPreferencesState> = combine(
        _activeUserId,
        _prefsUpdateTick
    ) { activeUid, _ ->
        val effectiveUid = activeUid ?: resolveCurrentFirebaseUid()
        val dsPrefs = safeDataStoreSnapshot()
        buildUserPreferencesState(dsPrefs, effectiveUid)
    }

    private fun readString(ds: Preferences?, key: Preferences.Key<String>): String? {
        if (sharedPrefs.contains(key.name)) {
            return sharedPrefs.getString(key.name, null)
        }
        return ds?.get(key)
    }

    private fun readBoolean(ds: Preferences?, key: Preferences.Key<Boolean>): Boolean? {
        if (sharedPrefs.contains(key.name)) {
            return sharedPrefs.getBoolean(key.name, false)
        }
        return ds?.get(key)
    }

    private fun readInt(ds: Preferences?, key: Preferences.Key<Int>): Int? {
        if (sharedPrefs.contains(key.name)) {
            return sharedPrefs.getInt(key.name, 0)
        }
        return ds?.get(key)
    }

    private fun readLong(ds: Preferences?, key: Preferences.Key<Long>): Long? {
        if (sharedPrefs.contains(key.name)) {
            return sharedPrefs.getLong(key.name, 0L)
        }
        return ds?.get(key)
    }

    private fun buildUserPreferencesState(
        prefs: Preferences?,
        effectiveUid: String?
    ): UserPreferencesState {
        val keys = UserScopedKeys(sanitizeUid(effectiveUid))

        // Check if this existing user has a scoped contact or a legacy contact whose linkId explicitly belongs to effectiveUid
        val hasScopedContact = !readString(prefs, keys.CONTACT_EMAIL).isNullOrBlank() ||
            !readString(prefs, keys.CONTACT_PHONE).isNullOrBlank() ||
            !readString(prefs, keys.CONTACT_NAME).isNullOrBlank()

        val safeUidForLink = effectiveUid?.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").orEmpty()
        val legacyLinkId = readString(prefs, GlobalKeys.LEGACY_CONTACT_LINK_ID).orEmpty()
        val canReadLegacyForThisUid = !hasScopedContact &&
            safeUidForLink.isNotBlank() &&
            legacyLinkId.startsWith("link_${safeUidForLink}_")

        val channelName = readString(prefs, keys.PREFERRED_CHANNEL)
            ?: (if (canReadLegacyForThisUid) readString(prefs, GlobalKeys.LEGACY_PREFERRED_CHANNEL) else null)
            ?: AlertChannelType.SOLOSIGNAL_POPUP.name
        val channel = try {
            AlertChannelType.valueOf(channelName)
        } catch (_: IllegalArgumentException) {
            AlertChannelType.SOLOSIGNAL_POPUP
        }

        val chatStatusName = readString(prefs, keys.CHAT_CONNECTION_STATUS)
            ?: GoogleChatConnectionStatus.NOT_CONFIGURED.name
        val rawChatStatus = try {
            GoogleChatConnectionStatus.valueOf(chatStatusName)
        } catch (_: IllegalArgumentException) {
            GoogleChatConnectionStatus.NOT_CONFIGURED
        }
        val chatStatus = if (rawChatStatus == GoogleChatConnectionStatus.CONNECTING) {
            GoogleChatConnectionStatus.NOT_CONFIGURED
        } else {
            rawChatStatus
        }

        val backendUrl = readString(prefs, keys.CHAT_BACKEND_URL) ?: ""
        val spaceName = readString(prefs, keys.CHAT_SPACE_NAME) ?: ""
        val initialSent = readBoolean(prefs, keys.CHAT_INITIAL_MESSAGE_SENT) ?: false
        val connectedKey = readString(prefs, keys.CHAT_CONNECTED_DESTINATION_KEY) ?: ""

        val verifiedStatus = if (chatStatus == GoogleChatConnectionStatus.CONNECTED &&
            (!initialSent || connectedKey.isBlank())
        ) {
            GoogleChatConnectionStatus.NOT_CONFIGURED
        } else {
            chatStatus
        }

        val rawVerificationStatus = readString(prefs, keys.CONTACT_VERIFICATION_STATUS)
            ?: (if (canReadLegacyForThisUid) readString(prefs, GlobalKeys.LEGACY_CONTACT_VERIFICATION_STATUS) else null)
        val verificationStatus = TrustedContactVerificationStatus.fromStorageKey(rawVerificationStatus)

        val sosAudioEnabled = readBoolean(prefs, keys.SOS_AUDIO_ENABLED) ?: false
        val sosAudioConsentGranted = readBoolean(prefs, keys.SOS_AUDIO_CONSENT_GRANTED) ?: false
        val sosAudioRetentionDays = (readInt(prefs, keys.SOS_AUDIO_RETENTION_DAYS) ?: 7).coerceIn(1, 30)
        val postTriggerAudioSeconds = (readInt(prefs, keys.POST_TRIGGER_AUDIO_SECONDS) ?: 3).coerceIn(0, 5)

        val trustedContact = if (hasScopedContact) {
            TrustedContact(
                userDisplayName = readString(prefs, keys.USER_DISPLAY_NAME) ?: "",
                contactName = readString(prefs, keys.CONTACT_NAME) ?: "",
                contactEmail = readString(prefs, keys.CONTACT_EMAIL) ?: "",
                contactPhone = readString(prefs, keys.CONTACT_PHONE) ?: "",
                relationship = readString(prefs, keys.CONTACT_RELATIONSHIP) ?: "Family",
                preferredChannel = channel,
                consentGranted = readBoolean(prefs, keys.CONSENT_GRANTED) ?: true,
                optionalLocationEnabled = readBoolean(prefs, keys.OPTIONAL_LOCATION_ENABLED) ?: true,
                phonePopUpAlertsEnabled = readBoolean(prefs, keys.PHONE_POPUP_ALERTS_ENABLED) ?: true,
                sosAudioEnabled = sosAudioEnabled,
                sosAudioConsentGranted = sosAudioConsentGranted,
                verificationStatus = verificationStatus,
                verificationDetail = readString(prefs, keys.CONTACT_VERIFICATION_DETAIL)
                    ?: verificationStatus.defaultDetail,
                recipientHasRegisteredAccount = readBoolean(prefs, keys.CONTACT_HAS_REGISTERED_ACCOUNT) ?: false,
                recipientAccepted = readBoolean(prefs, keys.CONTACT_ACCEPTED) ?: false,
                recipientRegisteredDeviceCount = readInt(prefs, keys.CONTACT_REGISTERED_DEVICE_COUNT) ?: 0,
                recipientNotificationsEnabled = readBoolean(prefs, keys.CONTACT_NOTIFICATIONS_ENABLED) ?: false,
                linkId = readString(prefs, keys.CONTACT_LINK_ID) ?: "",
                recipientUid = readString(prefs, keys.CONTACT_RECIPIENT_UID) ?: ""
            )
        } else if (canReadLegacyForThisUid) {
            TrustedContact(
                userDisplayName = readString(prefs, GlobalKeys.LEGACY_USER_DISPLAY_NAME) ?: "",
                contactName = readString(prefs, GlobalKeys.LEGACY_CONTACT_NAME) ?: "",
                contactEmail = readString(prefs, GlobalKeys.LEGACY_CONTACT_EMAIL) ?: "",
                contactPhone = readString(prefs, GlobalKeys.LEGACY_CONTACT_PHONE) ?: "",
                relationship = readString(prefs, GlobalKeys.LEGACY_CONTACT_RELATIONSHIP) ?: "Family",
                preferredChannel = channel,
                consentGranted = readBoolean(prefs, GlobalKeys.LEGACY_CONSENT_GRANTED) ?: true,
                optionalLocationEnabled = readBoolean(prefs, GlobalKeys.LEGACY_OPTIONAL_LOCATION_ENABLED) ?: true,
                phonePopUpAlertsEnabled = readBoolean(prefs, GlobalKeys.LEGACY_PHONE_POPUP_ALERTS_ENABLED) ?: true,
                sosAudioEnabled = sosAudioEnabled,
                sosAudioConsentGranted = sosAudioConsentGranted,
                verificationStatus = verificationStatus,
                verificationDetail = readString(prefs, GlobalKeys.LEGACY_CONTACT_VERIFICATION_DETAIL)
                    ?: verificationStatus.defaultDetail,
                recipientHasRegisteredAccount = readBoolean(prefs, GlobalKeys.LEGACY_CONTACT_HAS_REGISTERED_ACCOUNT) ?: false,
                recipientAccepted = readBoolean(prefs, GlobalKeys.LEGACY_CONTACT_ACCEPTED) ?: false,
                recipientRegisteredDeviceCount = readInt(prefs, GlobalKeys.LEGACY_CONTACT_REGISTERED_DEVICE_COUNT) ?: 0,
                recipientNotificationsEnabled = readBoolean(prefs, GlobalKeys.LEGACY_CONTACT_NOTIFICATIONS_ENABLED) ?: false,
                linkId = legacyLinkId,
                recipientUid = readString(prefs, GlobalKeys.LEGACY_CONTACT_RECIPIENT_UID) ?: ""
            )
        } else {
            // Blank contact for any new account or user without a saved contact
            TrustedContact(
                optionalLocationEnabled = readBoolean(prefs, keys.OPTIONAL_LOCATION_ENABLED) ?: true,
                sosAudioEnabled = sosAudioEnabled,
                sosAudioConsentGranted = sosAudioConsentGranted
            )
        }

        val onboardingDone = readBoolean(prefs, keys.ONBOARDING_COMPLETED)
            ?: (if (canReadLegacyForThisUid) readBoolean(prefs, GlobalKeys.LEGACY_ONBOARDING_COMPLETED) else null)
            ?: false

        return UserPreferencesState(
            onboardingCompleted = onboardingDone,
            countdownSeconds = (readInt(prefs, GlobalKeys.COUNTDOWN_SECONDS) ?: 15).coerceIn(10, 60),
            vibrationEnabled = readBoolean(prefs, GlobalKeys.VIBRATION_ENABLED) ?: true,
            sosAudioEnabled = sosAudioEnabled,
            sosAudioConsentGranted = sosAudioConsentGranted,
            sosAudioRetentionDays = sosAudioRetentionDays,
            postTriggerAudioSeconds = postTriggerAudioSeconds,
            gmailAccessToken = readString(prefs, keys.GMAIL_ACCESS_TOKEN) ?: "",
            deviceInstallationId = readString(prefs, GlobalKeys.DEVICE_INSTALLATION_ID) ?: "",
            fcmToken = readString(prefs, GlobalKeys.FCM_TOKEN) ?: "",
            trustedContact = trustedContact,
            googleChatConfig = GoogleChatAlertConfig(
                enabled = readBoolean(prefs, keys.CHAT_ENABLED) ?: false,
                backendEndpointUrl = backendUrl,
                spaceName = spaceName,
                spaceDisplayLabel = readString(prefs, keys.CHAT_SPACE_LABEL) ?: "",
                connectionStatus = verifiedStatus,
                statusDetail = readString(prefs, keys.CHAT_STATUS_DETAIL) ?: verifiedStatus.defaultDetail,
                initialConnectionMessageSent = initialSent,
                connectedDestinationKey = connectedKey,
                lastVerifiedEpochMillis = readLong(prefs, keys.CHAT_LAST_VERIFIED_MILLIS) ?: 0L
            )
        )
    }

    /**
     * Reads the current preferences state specifically for [uid] without leaking across accounts.
     */
    suspend fun getPreferencesForUser(uid: String?): UserPreferencesState {
        val prefs = safeDataStoreSnapshot()
        return buildUserPreferencesState(prefs, uid)
    }

    suspend fun getOrCreateDeviceInstallationId(): String {
        val current = getPreferencesForUser(_activeUserId.value).deviceInstallationId
        if (current.isNotBlank()) return current

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
                ?.take(32)
        } catch (_: Exception) {
            null
        }
        val generated = if (!androidId.isNullOrBlank() && androidId.length >= 6) {
            "dev_$androidId"
        } else {
            "dev_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        }
        sharedPrefs.edit().putString(GlobalKeys.DEVICE_INSTALLATION_ID.name, generated).commit()
        safeDataStoreEdit { prefs ->
            prefs[GlobalKeys.DEVICE_INSTALLATION_ID] = generated
        }
        notifyPrefsChanged()
        return generated
    }

    suspend fun saveFcmToken(token: String) {
        val trimmed = token.trim()
        sharedPrefs.edit().putString(GlobalKeys.FCM_TOKEN.name, trimmed).commit()
        safeDataStoreEdit { prefs ->
            prefs[GlobalKeys.FCM_TOKEN] = trimmed
        }
        notifyPrefsChanged()
    }

    suspend fun setOnboardingCompleted(completed: Boolean, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        sharedPrefs.edit().putBoolean(keys.ONBOARDING_COMPLETED.name, completed).commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.ONBOARDING_COMPLETED] = completed
        }
        notifyPrefsChanged()
    }

    suspend fun saveTrustedContact(contact: TrustedContact, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        val currentSosAudio = sharedPrefs.getBoolean(keys.SOS_AUDIO_ENABLED.name, contact.sosAudioEnabled)
        val currentSosConsent = sharedPrefs.getBoolean(keys.SOS_AUDIO_CONSENT_GRANTED.name, contact.sosAudioConsentGranted)
        val effectiveSosAudio = contact.sosAudioEnabled || currentSosAudio
        val effectiveSosConsent = contact.sosAudioConsentGranted || currentSosConsent

        sharedPrefs.edit()
            .putString(keys.USER_DISPLAY_NAME.name, contact.userDisplayName.trim())
            .putString(keys.CONTACT_NAME.name, contact.contactName.trim())
            .putString(keys.CONTACT_EMAIL.name, contact.contactEmail.trim().lowercase())
            .putString(keys.CONTACT_PHONE.name, contact.contactPhone.trim())
            .putString(keys.CONTACT_RELATIONSHIP.name, contact.relationship.trim().ifBlank { "Family" })
            .putString(keys.PREFERRED_CHANNEL.name, contact.preferredChannel.name)
            .putBoolean(keys.CONSENT_GRANTED.name, contact.consentGranted)
            .putBoolean(keys.OPTIONAL_LOCATION_ENABLED.name, contact.optionalLocationEnabled)
            .putBoolean(keys.PHONE_POPUP_ALERTS_ENABLED.name, contact.phonePopUpAlertsEnabled)
            .putBoolean(keys.SOS_AUDIO_ENABLED.name, effectiveSosAudio)
            .putBoolean(keys.SOS_AUDIO_CONSENT_GRANTED.name, effectiveSosConsent)
            .putString(keys.CONTACT_VERIFICATION_STATUS.name, contact.verificationStatus.name)
            .putString(keys.CONTACT_VERIFICATION_DETAIL.name, contact.verificationDetail)
            .putBoolean(keys.CONTACT_HAS_REGISTERED_ACCOUNT.name, contact.recipientHasRegisteredAccount)
            .putBoolean(keys.CONTACT_ACCEPTED.name, contact.recipientAccepted)
            .putInt(keys.CONTACT_REGISTERED_DEVICE_COUNT.name, contact.recipientRegisteredDeviceCount)
            .putBoolean(keys.CONTACT_NOTIFICATIONS_ENABLED.name, contact.recipientNotificationsEnabled)
            .putString(keys.CONTACT_LINK_ID.name, contact.linkId)
            .putString(keys.CONTACT_RECIPIENT_UID.name, contact.recipientUid)
            .commit()

        safeDataStoreEdit { prefs ->
            prefs[keys.USER_DISPLAY_NAME] = contact.userDisplayName.trim()
            prefs[keys.CONTACT_NAME] = contact.contactName.trim()
            prefs[keys.CONTACT_EMAIL] = contact.contactEmail.trim().lowercase()
            prefs[keys.CONTACT_PHONE] = contact.contactPhone.trim()
            prefs[keys.CONTACT_RELATIONSHIP] = contact.relationship.trim().ifBlank { "Family" }
            prefs[keys.PREFERRED_CHANNEL] = contact.preferredChannel.name
            prefs[keys.CONSENT_GRANTED] = contact.consentGranted
            prefs[keys.OPTIONAL_LOCATION_ENABLED] = contact.optionalLocationEnabled
            prefs[keys.PHONE_POPUP_ALERTS_ENABLED] = contact.phonePopUpAlertsEnabled
            prefs[keys.SOS_AUDIO_ENABLED] = effectiveSosAudio
            prefs[keys.SOS_AUDIO_CONSENT_GRANTED] = effectiveSosConsent
            prefs[keys.CONTACT_VERIFICATION_STATUS] = contact.verificationStatus.name
            prefs[keys.CONTACT_VERIFICATION_DETAIL] = contact.verificationDetail
            prefs[keys.CONTACT_HAS_REGISTERED_ACCOUNT] = contact.recipientHasRegisteredAccount
            prefs[keys.CONTACT_ACCEPTED] = contact.recipientAccepted
            prefs[keys.CONTACT_REGISTERED_DEVICE_COUNT] = contact.recipientRegisteredDeviceCount
            prefs[keys.CONTACT_NOTIFICATIONS_ENABLED] = contact.recipientNotificationsEnabled
            prefs[keys.CONTACT_LINK_ID] = contact.linkId
            prefs[keys.CONTACT_RECIPIENT_UID] = contact.recipientUid
        }
        notifyPrefsChanged()
    }

    suspend fun deleteTrustedContact(uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        val effectiveUid = uidOverride ?: _activeUserId.value ?: resolveCurrentFirebaseUid()
        val safeUidForLink = effectiveUid?.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").orEmpty()
        sharedPrefs.edit()
            .remove(keys.CONTACT_NAME.name)
            .remove(keys.CONTACT_EMAIL.name)
            .remove(keys.CONTACT_PHONE.name)
            .remove(keys.CONTACT_RELATIONSHIP.name)
            .remove(keys.USER_DISPLAY_NAME.name)
            .remove(keys.CONTACT_VERIFICATION_STATUS.name)
            .remove(keys.CONTACT_VERIFICATION_DETAIL.name)
            .remove(keys.CONTACT_HAS_REGISTERED_ACCOUNT.name)
            .remove(keys.CONTACT_ACCEPTED.name)
            .remove(keys.CONTACT_REGISTERED_DEVICE_COUNT.name)
            .remove(keys.CONTACT_NOTIFICATIONS_ENABLED.name)
            .remove(keys.CONTACT_LINK_ID.name)
            .remove(keys.CONTACT_RECIPIENT_UID.name)
            .commit()

        safeDataStoreEdit { prefs ->
            prefs.remove(keys.CONTACT_NAME)
            prefs.remove(keys.CONTACT_EMAIL)
            prefs.remove(keys.CONTACT_PHONE)
            prefs.remove(keys.CONTACT_RELATIONSHIP)
            prefs.remove(keys.USER_DISPLAY_NAME)
            prefs.remove(keys.CONTACT_VERIFICATION_STATUS)
            prefs.remove(keys.CONTACT_VERIFICATION_DETAIL)
            prefs.remove(keys.CONTACT_HAS_REGISTERED_ACCOUNT)
            prefs.remove(keys.CONTACT_ACCEPTED)
            prefs.remove(keys.CONTACT_REGISTERED_DEVICE_COUNT)
            prefs.remove(keys.CONTACT_NOTIFICATIONS_ENABLED)
            prefs.remove(keys.CONTACT_LINK_ID)
            prefs.remove(keys.CONTACT_RECIPIENT_UID)

            val legacyLink = prefs[GlobalKeys.LEGACY_CONTACT_LINK_ID].orEmpty()
            if (legacyLink.isBlank() || (safeUidForLink.isNotBlank() && legacyLink.startsWith("link_${safeUidForLink}_"))) {
                prefs.remove(GlobalKeys.LEGACY_CONTACT_NAME)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_EMAIL)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_PHONE)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_RELATIONSHIP)
                prefs.remove(GlobalKeys.LEGACY_USER_DISPLAY_NAME)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_VERIFICATION_STATUS)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_VERIFICATION_DETAIL)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_HAS_REGISTERED_ACCOUNT)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_ACCEPTED)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_REGISTERED_DEVICE_COUNT)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_NOTIFICATIONS_ENABLED)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_LINK_ID)
                prefs.remove(GlobalKeys.LEGACY_CONTACT_RECIPIENT_UID)
            }
        }
        notifyPrefsChanged()
    }

    suspend fun saveGmailAccessToken(token: String, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        val trimmed = token.trim()
        sharedPrefs.edit().putString(keys.GMAIL_ACCESS_TOKEN.name, trimmed).commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.GMAIL_ACCESS_TOKEN] = trimmed
        }
        notifyPrefsChanged()
    }

    suspend fun clearAllUserData(uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        deleteTrustedContact(uidOverride)
        sharedPrefs.edit()
            .remove(keys.PREFERRED_CHANNEL.name)
            .remove(keys.ONBOARDING_COMPLETED.name)
            .remove(keys.GMAIL_ACCESS_TOKEN.name)
            .remove(keys.SOS_AUDIO_ENABLED.name)
            .remove(keys.SOS_AUDIO_CONSENT_GRANTED.name)
            .remove(keys.SOS_AUDIO_RETENTION_DAYS.name)
            .remove(keys.POST_TRIGGER_AUDIO_SECONDS.name)
            .remove(keys.CHAT_ENABLED.name)
            .remove(keys.CHAT_SPACE_NAME.name)
            .remove(keys.CHAT_SPACE_LABEL.name)
            .remove(keys.CHAT_CONNECTION_STATUS.name)
            .remove(keys.CHAT_STATUS_DETAIL.name)
            .remove(keys.CHAT_INITIAL_MESSAGE_SENT.name)
            .remove(keys.CHAT_CONNECTED_DESTINATION_KEY.name)
            .remove(keys.CHAT_LAST_VERIFIED_MILLIS.name)
            .commit()
        safeDataStoreEdit { prefs ->
            prefs.remove(keys.PREFERRED_CHANNEL)
            prefs.remove(keys.ONBOARDING_COMPLETED)
            prefs.remove(keys.GMAIL_ACCESS_TOKEN)
            prefs.remove(keys.SOS_AUDIO_ENABLED)
            prefs.remove(keys.SOS_AUDIO_CONSENT_GRANTED)
            prefs.remove(keys.SOS_AUDIO_RETENTION_DAYS)
            prefs.remove(keys.POST_TRIGGER_AUDIO_SECONDS)
            prefs.remove(keys.CHAT_ENABLED)
            prefs.remove(keys.CHAT_SPACE_NAME)
            prefs.remove(keys.CHAT_SPACE_LABEL)
            prefs.remove(keys.CHAT_CONNECTION_STATUS)
            prefs.remove(keys.CHAT_STATUS_DETAIL)
            prefs.remove(keys.CHAT_INITIAL_MESSAGE_SENT)
            prefs.remove(keys.CHAT_CONNECTED_DESTINATION_KEY)
            prefs.remove(keys.CHAT_LAST_VERIFIED_MILLIS)
        }
        notifyPrefsChanged()
    }

    suspend fun setOptionalLocationEnabled(enabled: Boolean, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        sharedPrefs.edit().putBoolean(keys.OPTIONAL_LOCATION_ENABLED.name, enabled).commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.OPTIONAL_LOCATION_ENABLED] = enabled
        }
        notifyPrefsChanged()
    }

    suspend fun setSosAudioEnabled(
        enabled: Boolean,
        consentGranted: Boolean = enabled,
        uidOverride: String? = null
    ) {
        val keys = scopedKeysFor(uidOverride)
        val effectiveConsent = if (enabled) consentGranted else sharedPrefs.getBoolean(keys.SOS_AUDIO_CONSENT_GRANTED.name, false)
        sharedPrefs.edit()
            .putBoolean(keys.SOS_AUDIO_ENABLED.name, enabled && effectiveConsent)
            .putBoolean(keys.SOS_AUDIO_CONSENT_GRANTED.name, effectiveConsent)
            .commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.SOS_AUDIO_ENABLED] = enabled && effectiveConsent
            prefs[keys.SOS_AUDIO_CONSENT_GRANTED] = effectiveConsent
        }
        notifyPrefsChanged()
    }

    suspend fun setSosAudioRetentionDays(days: Int, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        val coerced = days.coerceIn(1, 30)
        sharedPrefs.edit().putInt(keys.SOS_AUDIO_RETENTION_DAYS.name, coerced).commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.SOS_AUDIO_RETENTION_DAYS] = coerced
        }
        notifyPrefsChanged()
    }

    suspend fun setPostTriggerAudioSeconds(seconds: Int, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        val coerced = seconds.coerceIn(0, 5)
        sharedPrefs.edit().putInt(keys.POST_TRIGGER_AUDIO_SECONDS.name, coerced).commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.POST_TRIGGER_AUDIO_SECONDS] = coerced
        }
        notifyPrefsChanged()
    }

    suspend fun saveGoogleChatConfig(config: GoogleChatAlertConfig, uidOverride: String? = null) {
        val keys = scopedKeysFor(uidOverride)
        sharedPrefs.edit()
            .putBoolean(keys.CHAT_ENABLED.name, config.enabled)
            .putString(keys.CHAT_BACKEND_URL.name, config.normalizedEndpointUrl)
            .putString(keys.CHAT_SPACE_NAME.name, config.normalizedSpaceName)
            .putString(keys.CHAT_SPACE_LABEL.name, config.spaceDisplayLabel.trim())
            .putString(keys.CHAT_CONNECTION_STATUS.name, config.connectionStatus.name)
            .putString(keys.CHAT_STATUS_DETAIL.name, config.statusDetail)
            .putBoolean(keys.CHAT_INITIAL_MESSAGE_SENT.name, config.initialConnectionMessageSent)
            .putString(keys.CHAT_CONNECTED_DESTINATION_KEY.name, config.connectedDestinationKey)
            .putLong(keys.CHAT_LAST_VERIFIED_MILLIS.name, config.lastVerifiedEpochMillis)
            .commit()
        safeDataStoreEdit { prefs ->
            prefs[keys.CHAT_ENABLED] = config.enabled
            prefs[keys.CHAT_BACKEND_URL] = config.normalizedEndpointUrl
            prefs[keys.CHAT_SPACE_NAME] = config.normalizedSpaceName
            prefs[keys.CHAT_SPACE_LABEL] = config.spaceDisplayLabel.trim()
            prefs[keys.CHAT_CONNECTION_STATUS] = config.connectionStatus.name
            prefs[keys.CHAT_STATUS_DETAIL] = config.statusDetail
            prefs[keys.CHAT_INITIAL_MESSAGE_SENT] = config.initialConnectionMessageSent
            prefs[keys.CHAT_CONNECTED_DESTINATION_KEY] = config.connectedDestinationKey
            prefs.remove(GlobalKeys.LEGACY_ONBOARDING_COMPLETED)
            prefs[keys.CHAT_LAST_VERIFIED_MILLIS] = config.lastVerifiedEpochMillis
        }
        notifyPrefsChanged()
    }

    suspend fun setCountdownSeconds(seconds: Int) {
        val coerced = seconds.coerceIn(10, 60)
        sharedPrefs.edit().putInt(GlobalKeys.COUNTDOWN_SECONDS.name, coerced).commit()
        safeDataStoreEdit { prefs ->
            prefs[GlobalKeys.COUNTDOWN_SECONDS] = coerced
        }
        notifyPrefsChanged()
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(GlobalKeys.VIBRATION_ENABLED.name, enabled).commit()
        safeDataStoreEdit { prefs ->
            prefs[GlobalKeys.VIBRATION_ENABLED] = enabled
        }
        notifyPrefsChanged()
    }

    companion object {
        private const val LOCAL_UNAUTHENTICATED_SCOPE = "u_local_unauthenticated"
    }
}
