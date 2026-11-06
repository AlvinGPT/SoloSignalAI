package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import androidx.core.content.ContextCompat
import com.example.alerts.AlertMessagePayload
import com.example.domain.AlertChannelType
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.QrContactPayload
import com.example.domain.RecipientDeviceInfo
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.domain.TrustedContactVerificationStatus
import androidx.compose.ui.graphics.Color
import com.example.ui.components.PastelCard
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.WarmMistBackground

/**
 * Clean, minimal Emergency Contact Screen for SoloSignal AI:
 * - Accurate distinction between Connected, Pending, Registered (signed-in device), and Invite.
 * - Compact QR Code and Scan QR icons beside the Contacts heading for fast, verified contact setup.
 * - Non-clipping short labels and responsive button layouts for small screens.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrustedContactScreen(
    currentContact: TrustedContact,
    googleChatConfig: GoogleChatAlertConfig,
    deliveryStatus: DeliveryConfigStatus,
    deliveryStatusDetail: String,
    requiresGmailAuthorization: Boolean,
    hasGmailTokenConfigured: Boolean,
    lastMessagePreview: AlertMessagePayload?,
    latestLocationSnapshot: GpsLocationSnapshot?,
    locationDiagnostics: String,
    isCapturingLocation: Boolean,
    incomingContactRequests: List<TrustedContactRequestItem>,
    registeredDevices: List<RecipientDeviceInfo>,
    fcmRegistrationStatus: String,
    isVerifyingTrustedContact: Boolean,
    contactSyncError: String? = null,
    isInitialSetup: Boolean = false,
    currentUserUid: String = "",
    currentUserDisplayName: String = "",
    currentUserEmail: String = "",
    currentUserPhone: String = "",
    initialScannedQrPayload: QrContactPayload? = null,
    onCompleteInitialSetup: (() -> Unit)? = null,
    onVerifyAndSaveContact: (TrustedContact, Boolean, Boolean) -> Unit,
    onAcceptIncomingContactRequest: (String) -> Unit,
    onDeleteContact: () -> Unit,
    onRefreshFcmDevice: () -> Unit,
    onSendTestAlert: () -> Unit,
    onRefreshLocation: () -> Unit,
    onSaveGmailAccessToken: (String) -> Unit,
    onSaveAndConnectGoogleChat: (Boolean, String, String, String) -> Unit,
    onSendManualGoogleChatVerification: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var contactName by remember(currentContact.contactName, initialScannedQrPayload) {
        mutableStateOf(initialScannedQrPayload?.displayName ?: currentContact.contactName)
    }
    var contactEmail by remember(currentContact.contactEmail, initialScannedQrPayload) {
        mutableStateOf(initialScannedQrPayload?.email ?: currentContact.contactEmail)
    }
    var contactPhone by remember(currentContact.contactPhone, initialScannedQrPayload) {
        mutableStateOf(
            initialScannedQrPayload?.phone?.takeIf { it.isNotBlank() }
                ?: currentContact.contactPhone
        )
    }
    var relationship by remember(currentContact.relationship) {
        mutableStateOf(currentContact.relationship.ifBlank { "Family" })
    }
    var shareLocation by remember(currentContact.optionalLocationEnabled) {
        mutableStateOf(currentContact.optionalLocationEnabled)
    }
    var consentGranted by remember(currentContact.consentGranted, initialScannedQrPayload) {
        mutableStateOf(if (initialScannedQrPayload != null) true else currentContact.consentGranted)
    }
    var formError by remember { mutableStateOf<String?>(null) }
    var inviteStatusFeedback by remember { mutableStateOf<String?>(null) }
    var qrSaveSuccessMessage by remember { mutableStateOf<String?>(null) }

    var showPersonalQrDialog by remember { mutableStateOf(false) }
    var showScanQrDialog by remember { mutableStateOf(false) }
    var pendingScannedQrPayload by remember(initialScannedQrPayload) {
        mutableStateOf(initialScannedQrPayload)
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            val (pickedName, pickedEmail, pickedPhone) = queryDeviceContact(context, contactUri)
            if (!pickedName.isNullOrBlank()) contactName = pickedName
            if (!pickedEmail.isNullOrBlank()) contactEmail = pickedEmail
            if (!pickedPhone.isNullOrBlank()) contactPhone = pickedPhone
            formError = null
        }
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        contactPickerLauncher.launch(null)
    }

    fun launchContactPicker() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactPickerLauncher.launch(null)
        } else {
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    fun sendAppInvitation(emailOrPhone: String, recipientName: String) {
        val inviteSubject = "Join me on SoloSignal AI for emergency alerts"
        val inviteBody = "Hi $recipientName, I've listed you as my emergency contact on SoloSignal AI. " +
            "Please install the app and sign in so your phone can receive my emergency SOS alerts with my live GPS location."

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, inviteSubject)
            putExtra(Intent.EXTRA_TEXT, inviteBody)
            if (emailOrPhone.contains("@")) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(emailOrPhone.trim()))
            }
        }
        try {
            context.startActivity(Intent.createChooser(shareIntent, "Invite via"))
            inviteStatusFeedback = "Invitation sent to $recipientName."
        } catch (_: Exception) {
            inviteStatusFeedback = "Invitation marked as sent for $recipientName."
        }
    }

    fun handleQrScanDecoded(payload: QrContactPayload) {
        showScanQrDialog = false
        contactName = payload.displayName
        contactEmail = payload.email
        if (payload.phone.isNotBlank()) {
            contactPhone = payload.phone
        }
        consentGranted = true
        formError = null
        qrSaveSuccessMessage = null
        pendingScannedQrPayload = payload
    }

    fun confirmAndSaveScannedQrContact(payload: QrContactPayload) {
        val trimmedName = payload.displayName.trim()
        val trimmedEmail = payload.email.trim().lowercase()
        val trimmedPhone = payload.phone.trim()

        if (trimmedName.isBlank() || (trimmedEmail.isBlank() && trimmedPhone.isBlank())) {
            formError = "Scanned QR code is missing required contact details."
            pendingScannedQrPayload = null
            return
        }

        val isDuplicate = currentContact.isValid && (
            (trimmedEmail.isNotBlank() && currentContact.contactEmail.trim().equals(trimmedEmail, ignoreCase = true)) ||
                (trimmedPhone.isNotBlank() && currentContact.contactPhone.trim().isNotBlank() &&
                    currentContact.contactPhone.filter { it.isDigit() } == trimmedPhone.filter { it.isDigit() })
            )
        if (isDuplicate) {
            formError = "$trimmedName is already saved as your emergency contact."
            pendingScannedQrPayload = null
            return
        }

        val updated = TrustedContact(
            userDisplayName = currentUserDisplayName.ifBlank { currentContact.userDisplayName },
            contactName = trimmedName,
            contactEmail = trimmedEmail,
            contactPhone = trimmedPhone,
            relationship = relationship,
            optionalLocationEnabled = shareLocation,
            consentGranted = true,
            preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP,
            recipientUid = payload.uid
        )

        contactName = trimmedName
        contactEmail = trimmedEmail
        contactPhone = trimmedPhone
        consentGranted = true
        formError = null
        pendingScannedQrPayload = null
        qrSaveSuccessMessage = "Saved $trimmedName as your emergency contact."
        onVerifyAndSaveContact(updated, true, false)
    }

    if (showPersonalQrDialog) {
        val resolvedDisplayName = currentUserDisplayName.ifBlank {
            currentContact.userDisplayName.ifBlank {
                if (currentUserEmail.contains("@")) currentUserEmail.substringBefore("@") else "SoloSignal User"
            }
        }
        PersonalQrCodeDialog(
            userUid = currentUserUid,
            userDisplayName = resolvedDisplayName,
            userEmail = currentUserEmail,
            initialPhone = currentUserPhone,
            onDismiss = { showPersonalQrDialog = false }
        )
    }

    if (showScanQrDialog) {
        ScanQrCodeDialog(
            scannerUid = currentUserUid,
            scannerEmail = currentUserEmail,
            existingContact = currentContact,
            onValidQrScanned = { payload -> handleQrScanDecoded(payload) },
            onDismiss = { showScanQrDialog = false }
        )
    }

    val activeConfirmationPayload = pendingScannedQrPayload
    if (activeConfirmationPayload != null) {
        ConfirmScannedContactDialog(
            scannedPayload = activeConfirmationPayload,
            selectedRelationship = relationship,
            onRelationshipChange = { relationship = it },
            onConfirmAndAdd = { confirmAndSaveScannedQrContact(activeConfirmationPayload) },
            onCancel = { pendingScannedQrPayload = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("trusted_contact_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with compact, premium My QR Code & Scan QR icons beside the Contacts heading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!currentContact.isValid || isInitialSetup) {
                            "Add Emergency Contact"
                        } else {
                            "Emergency Contact"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("add_emergency_contact_heading")
                    )
                    Text(
                        text = if (isInitialSetup) {
                            "Welcome! Enter or scan your emergency contact to check if they have a SoloSignal AI account."
                        } else {
                            "Person who receives your SOS and fall alerts."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isVerifyingTrustedContact) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = DeepCharcoal,
                            strokeWidth = 2.dp
                        )
                    }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.tt_show_qr)) } },
                        state = rememberTooltipState()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = CardSurfaceWhite,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .size(48.dp)
                                .border(1.dp, HairlineDivider, CircleShape)
                        ) {
                            IconButton(
                                onClick = {
                                    qrSaveSuccessMessage = null
                                    showPersonalQrDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics {
                                        contentDescription = "My QR Code: Show personal QR code to share contact"
                                    }
                                    .testTag("my_qr_code_icon_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = "My QR Code",
                                    tint = DeepCharcoal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.tt_scan_qr)) } },
                        state = rememberTooltipState()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = DeepCharcoal,
                            tonalElevation = 1.dp,
                            modifier = Modifier.size(48.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    qrSaveSuccessMessage = null
                                    showScanQrDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics {
                                        contentDescription = "Scan QR Code: Scan another SoloSignal AI user's QR code"
                                    }
                                    .testTag("scan_qr_code_icon_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Scan QR Code",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (qrSaveSuccessMessage != null) {
                PastelCard(
                    containerColor = MintSoftSurface,
                    borderColor = DeepCharcoal.copy(alpha = 0.25f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("qr_contact_saved_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = qrSaveSuccessMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepCharcoal,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("qr_contact_saved_text")
                        )
                    }
                }
            }

            if (isInitialSetup && onCompleteInitialSetup != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCompleteInitialSetup,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("complete_initial_contact_setup_button")
                    ) {
                        Text(
                            text = if (currentContact.isValid) "Continue to Home" else "Skip for now",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (!contactSyncError.isNullOrBlank()) {
                PastelCard(
                    containerColor = CoralSoftSurface,
                    borderColor = CoralUrgentHighlight,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("contact_sync_error_banner")
                ) {
                    Text(
                        text = contactSyncError,
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 1. Saved Emergency Contact Card
            if (currentContact.isValid) {
                val (badgeLabel, badgeColor) = resolveContactBadge(currentContact)
                val isUnregistered = !currentContact.recipientHasRegisteredAccount &&
                    !currentContact.recipientAccepted &&
                    currentContact.verificationStatus == TrustedContactVerificationStatus.INVITATION_NEEDED
                val isInvitePending = !currentContact.recipientAccepted &&
                    currentContact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING
                val isRegisteredNeedsConnect = currentContact.recipientHasRegisteredAccount &&
                    !currentContact.recipientAccepted &&
                    currentContact.verificationStatus == TrustedContactVerificationStatus.REGISTERED
                val isRequestPending = currentContact.recipientHasRegisteredAccount &&
                    !currentContact.recipientAccepted &&
                    currentContact.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING

                PastelCard(containerColor = WarmMistBackground) {
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
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(LavenderSoftSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = DeepCharcoal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                SectionEyebrow(text = "SAVED CONTACT")
                                Text(
                                    text = "${currentContact.contactName} (${currentContact.relationship})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepCharcoal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentContact.contactEmail.ifBlank { currentContact.contactPhone },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeutralSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        StatusPillBadge(
                            label = badgeLabel,
                            containerColor = badgeColor,
                            modifier = Modifier.testTag("trusted_contact_verification_badge")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val conciseStatusNote = when {
                        currentContact.isReadyForPushAlerts ->
                            "Connected and signed in on ${currentContact.recipientRegisteredDeviceCount} device(s)."
                        currentContact.recipientAccepted &&
                            currentContact.verificationStatus == TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED ->
                            "Connected to your account, currently offline (no active signed-in device)."
                        currentContact.recipientAccepted &&
                            currentContact.verificationStatus == TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED ->
                            "Connected to your account, currently offline (notifications disabled on their phone)."
                        currentContact.recipientAccepted ->
                            "Connected to your account."
                        isRequestPending ->
                            "Existing SoloSignal account found. Link request sent and awaiting acceptance."
                        isRegisteredNeedsConnect ->
                            "Existing SoloSignal account found (${currentContact.contactEmail}). Link this account to send alerts."
                        isInvitePending ->
                            "Invitation sent. Waiting for ${currentContact.contactName} to sign in and accept."
                        else ->
                            currentContact.verificationDetail
                    }

                    Text(
                        text = conciseStatusNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate,
                        modifier = Modifier.testTag("trusted_contact_verification_detail")
                    )

                    // Show unregistered explanation & Invite action ONLY when genuinely unregistered & not yet invited
                    if (isUnregistered) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = TrustedContactVerificationStatus.UNREGISTERED_CONTACT_EXPLANATION,
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepCharcoal,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("unregistered_contact_explanation_text")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    sendAppInvitation(
                                        emailOrPhone = currentContact.contactEmail.ifBlank { currentContact.contactPhone },
                                        recipientName = currentContact.contactName
                                    )
                                    onVerifyAndSaveContact(currentContact, false, true)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepCharcoal,
                                    contentColor = Color.White,
                                    disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                    disabledContentColor = Color.White.copy(alpha = 0.9f)
                                ),
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("invite_trusted_contact_button")
                                    .testTag("send_invitation_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_invite), color = Color.White, maxLines = 1, softWrap = false)
                            }

                            OutlinedButton(
                                onClick = {
                                    onRefreshFcmDevice()
                                    onVerifyAndSaveContact(currentContact, false, false)
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("refresh_contact_status_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                            }
                        }
                    } else if (isRegisteredNeedsConnect || isRequestPending || isInvitePending || currentContact.recipientAccepted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isRegisteredNeedsConnect) {
                                Button(
                                    onClick = { onVerifyAndSaveContact(currentContact, true, false) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DeepCharcoal,
                                        contentColor = Color.White,
                                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                        disabledContentColor = Color.White.copy(alpha = 0.9f)
                                    ),
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("request_contact_permission_button")
                                ) {
                                    Text(stringResource(R.string.action_link_account), color = Color.White, maxLines = 1, softWrap = false)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    onRefreshFcmDevice()
                                    onVerifyAndSaveContact(
                                        currentContact,
                                        isRequestPending,
                                        isInvitePending
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("refresh_contact_status_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    if (inviteStatusFeedback != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = inviteStatusFeedback!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepCharcoal,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = HairlineDivider)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSendTestAlert,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("contacts_send_test_alert_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send test", color = Color.White, maxLines = 1, softWrap = false)
                        }

                        OutlinedButton(
                            onClick = onDeleteContact,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralUrgentHighlight),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("delete_contact_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_remove), maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Alert tone: Emergency Buzzer (rings contact device with distinctive loud buzzer)",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate
                    )
                }
            }

            // 2. Incoming Requests from Other Users
            val pending = incomingContactRequests.filter { !it.recipientAccepted }
            if (pending.isNotEmpty()) {
                PastelCard(containerColor = LavenderSoftSurface) {
                    SectionEyebrow(text = "INCOMING REQUESTS")
                    Spacer(modifier = Modifier.height(6.dp))
                    pending.forEach { req ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = req.senderName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepCharcoal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = req.senderEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeutralSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onAcceptIncomingContactRequest(req.linkId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepCharcoal,
                                    contentColor = Color.White,
                                    disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                    disabledContentColor = Color.White.copy(alpha = 0.9f)
                                ),
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("accept_contact_request_${req.linkId}")
                            ) {
                                Text(stringResource(R.string.action_accept), color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }

            // 3. Add or Edit Contact Form
            PastelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionEyebrow(
                        text = if (currentContact.isValid) "EDIT CONTACT" else "ADD CONTACT",
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = { launchContactPicker() },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("pick_from_contacts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContactPhone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Contacts", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it; formError = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_name_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = contactEmail,
                    onValueChange = { contactEmail = it; formError = null },
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_email_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it; formError = null },
                    label = { Text("Phone (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_phone_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Relationship",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeutralSlate
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Family", "Partner", "Friend", "Caregiver").forEach { rel ->
                        FilterChip(
                            selected = relationship.equals(rel, ignoreCase = true),
                            onClick = { relationship = rel },
                            label = { Text(rel, maxLines = 1, softWrap = false) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepCharcoal,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("relationship_chip_${rel.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Share GPS Location",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = DeepCharcoal
                        )
                        Text(
                            text = "Include Google Maps link in SOS alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                    }
                    Switch(
                        checked = shareLocation,
                        onCheckedChange = { shareLocation = it },
                        modifier = Modifier.testTag("contact_location_toggle")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { consentGranted = !consentGranted },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = consentGranted,
                        onCheckedChange = { consentGranted = it },
                        modifier = Modifier.testTag("consent_checkbox")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "I have permission to add this emergency contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepCharcoal,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (formError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralUrgentHighlight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("contact_validation_error")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (contactName.isBlank()) {
                            formError = "Enter a contact name."
                            return@Button
                        }
                        if (contactEmail.isBlank() && contactPhone.isBlank()) {
                            formError = "Enter an email or phone number."
                            return@Button
                        }
                        if (!consentGranted) {
                            formError = "Confirm permission to add this contact."
                            return@Button
                        }
                        val isSameEmail = currentContact.contactEmail.trim()
                            .equals(contactEmail.trim(), ignoreCase = true)
                        val updated = if (isSameEmail) {
                            currentContact.copy(
                                contactName = contactName.trim(),
                                contactEmail = contactEmail.trim(),
                                contactPhone = contactPhone.trim(),
                                relationship = relationship,
                                optionalLocationEnabled = shareLocation,
                                consentGranted = true,
                                preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP
                            )
                        } else {
                            TrustedContact(
                                userDisplayName = currentContact.userDisplayName,
                                contactName = contactName.trim(),
                                contactEmail = contactEmail.trim(),
                                contactPhone = contactPhone.trim(),
                                relationship = relationship,
                                optionalLocationEnabled = shareLocation,
                                consentGranted = true,
                                preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP
                            )
                        }
                        val sendPermOnSave = isSameEmail &&
                            (currentContact.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING ||
                                currentContact.recipientAccepted)
                        val keepInviteSent = isSameEmail &&
                            currentContact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING
                        onVerifyAndSaveContact(updated, sendPermOnSave, keepInviteSent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = Color.White,
                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("save_contact_button")
                ) {
                    Text(
                        text = if (currentContact.isValid) "Save" else "Save Contact",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Reads name, email, and phone from an Android contact Uri.
 */
private fun queryDeviceContact(context: Context, contactUri: Uri): Triple<String?, String?, String?> {
    var name: String? = null
    var email: String? = null
    var phone: String? = null

    try {
        context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                if (idIdx >= 0) {
                    val contactId = cursor.getString(idIdx)

                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { emailCursor ->
                        if (emailCursor.moveToFirst()) {
                            val emailIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            if (emailIdx >= 0) email = emailCursor.getString(emailIdx)
                        }
                    }

                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { phoneCursor ->
                        if (phoneCursor.moveToFirst()) {
                            val phoneIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (phoneIdx >= 0) phone = phoneCursor.getString(phoneIdx)
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {
    }

    return Triple(name, email, phone)
}
