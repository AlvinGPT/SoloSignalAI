/**
 * SoloSignal AI — Secure Firebase Cloud Functions for:
 * 1. Privacy-Preserving Trusted Contact Verification & Request Workflow (`verifyOrRequestTrustedContact`).
 * 2. Real Multi-Device High-Priority FCM Emergency Push Notifications (`dispatchEmergencyFcmToRecipientDevices`)
 *    with automatic stale/invalid token cleanup and relationship authorization checks.
 * 3. Real Server-Side Emergency Email Delivery via Gmail API (`sendSoloSignalEmergencyEmail` & `onEmergencyAlertCreated`).
 * 4. Official Google Chat API Space Alerts (`sendSoloSignalGoogleChatAlert`).
 *
 * Deploy with:
 *   firebase deploy --only functions
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { GoogleAuth } = require("google-auth-library");

admin.initializeApp();

const CHAT_BOT_SCOPE = "https://www.googleapis.com/auth/chat.bot";
const INITIAL_CONNECTION_MESSAGE =
  "Hi! SoloSignal AI is connected. This chat will receive possible-fall alerts when the user needs assistance. This is a connection test, not an emergency.";

const EMAIL_REGEX = /^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function isValidEmailAddress(email) {
  return typeof email === "string" && EMAIL_REGEX.test(email.trim());
}

function escapeHtml(text) {
  return String(text || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function buildLinkId(senderUid, recipientEmail) {
  const safeEmail = String(recipientEmail || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "_");
  return `link_${senderUid}_${safeEmail}`.slice(0, 120);
}

/**
 * Dispatches high-priority FCM push notifications to all registered devices of an authorized recipient,
 * and removes stale/invalid device tokens automatically.
 */
async function dispatchEmergencyFcmToRecipientDevices({
  db,
  alertId,
  senderUid,
  senderName,
  senderEmail,
  recipientName,
  recipientEmail,
  isTestAlert,
  subjectLine,
  bodyText,
  triggerReason,
  locationSummary,
  googleMapsUrl,
  latitude,
  longitude
}) {
  const normalizedRecipientEmail = String(recipientEmail || "").trim().toLowerCase();
  if (!isValidEmailAddress(normalizedRecipientEmail)) {
    return { fcmStatus: "NO_REGISTERED_DEVICE", deliveredCount: 0, messageIds: [] };
  }

  // 1. Verify contact relationship authorization in /trusted_contact_links
  const linkId = buildLinkId(senderUid, normalizedRecipientEmail);
  const linkSnap = await db.collection("trusted_contact_links").doc(linkId).get();
  let recipientUid = null;
  let isAuthorized = false;

  if (linkSnap.exists) {
    const linkData = linkSnap.data() || {};
    if (linkData.recipientAccepted === true && linkData.status !== "REVOKED") {
      isAuthorized = true;
      recipientUid = linkData.recipientUid || null;
    }
  }

  // Allow self-test on the sender's own authenticated account
  if (!recipientUid) {
    try {
      const userRecord = await admin.auth().getUserByEmail(normalizedRecipientEmail);
      if (userRecord && userRecord.uid === senderUid) {
        recipientUid = userRecord.uid;
        isAuthorized = true;
      } else if (userRecord && isAuthorized) {
        recipientUid = userRecord.uid;
      }
    } catch (_) {}
  }

  if (!isAuthorized || !recipientUid) {
    return { fcmStatus: "NO_REGISTERED_DEVICE", deliveredCount: 0, messageIds: [] };
  }

  // 2. Fetch all registered devices from private subcollection /users/{recipientUid}/devices
  const devicesSnap = await db
    .collection("users")
    .doc(recipientUid)
    .collection("devices")
    .get();

  if (devicesSnap.empty) {
    return { fcmStatus: "NO_REGISTERED_DEVICE", deliveredCount: 0, messageIds: [] };
  }

  const messageIds = [];
  const staleDocRefs = [];

  for (const deviceDoc of devicesSnap.docs) {
    const deviceData = deviceDoc.data() || {};
    const token = String(deviceData.fcmToken || "").trim();
    if (!token) continue;

    try {
      const fcmId = await admin.messaging().send({
        token,
        notification: {
          title:
            subjectLine ||
            (isTestAlert
              ? `TEST Pop-up Alert from ${senderName}`
              : `⚠️ Emergency Check-In: ${senderName} may need help`),
          body: `${triggerReason || "Possible fall detected"}. ${locationSummary || ""}`.trim()
        },
        android: {
          priority: "high",
          notification: {
            channelId: "solosignal_recipient_emergency_buzzer_v1",
            sound: "emergency_buzzer",
            priority: "max",
            defaultSound: false,
            defaultVibrateTimings: true,
            visibility: "public"
          }
        },
        data: {
          alertId: String(alertId || ""),
          senderUid: String(senderUid || ""),
          senderName: String(senderName || "SoloSignal User"),
          senderEmail: String(senderEmail || ""),
          recipientName: String(recipientName || "Trusted Contact"),
          recipientEmail: normalizedRecipientEmail,
          isTestAlert: String(Boolean(isTestAlert)),
          subjectLine: String(subjectLine || ""),
          bodyText: String(bodyText || ""),
          triggerReason: String(triggerReason || "Emergency check-in alert"),
          locationSummary: String(locationSummary || "Location unavailable"),
          googleMapsUrl: String(googleMapsUrl || ""),
          latitude: latitude != null ? String(latitude) : "",
          longitude: longitude != null ? String(longitude) : "",
          createdAtMillis: String(Date.now())
        }
      });
      if (fcmId) {
        messageIds.push(fcmId);
      }
    } catch (fcmErr) {
      const code = (fcmErr && fcmErr.code) || "";
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        staleDocRefs.push(deviceDoc.ref);
      }
    }
  }

  // 3. Remove invalid or stale device tokens
  for (const ref of staleDocRefs) {
    try {
      await ref.delete();
    } catch (_) {}
  }

  return {
    fcmStatus: messageIds.length > 0 ? "SENT" : "FAILED",
    deliveredCount: messageIds.length,
    messageIds
  };
}

/**
 * HTTPS Cloud Function: `verifyOrRequestTrustedContact`
 * Privacy-preserving backend lookup and contact-request creation.
 * Never exposes a public user directory or raw device tokens.
 */
exports.verifyOrRequestTrustedContact = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "Method Not Allowed. Use POST." });
  }

  try {
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      return res.status(401).json({ ok: false, error: "Missing Firebase Authentication Bearer token." });
    }

    const idToken = authHeader.substring("Bearer ".length).trim();
    const decodedToken = await admin.auth().verifyIdToken(idToken);
    const senderUid = decodedToken.uid;
    const senderEmail = String(decodedToken.email || "").trim().toLowerCase();
    const senderName = String(req.body?.senderName || decodedToken.name || senderEmail || "SoloSignal User").trim();

    const recipientEmail = String(req.body?.recipientEmail || "").trim().toLowerCase();
    const recipientName = String(req.body?.recipientName || "Trusted Contact").trim();
    const recipientPhone = String(req.body?.recipientPhone || "").trim();
    const relationship = String(req.body?.relationship || "Family").trim();
    const sendContactRequest = Boolean(req.body?.sendContactRequest);

    if (!isValidEmailAddress(recipientEmail)) {
      return res.status(400).json({ ok: false, error: "Enter a valid email address." });
    }

    const db = admin.firestore();
    let recipientRecord = null;
    try {
      recipientRecord = await admin.auth().getUserByEmail(recipientEmail);
    } catch (_) {
      recipientRecord = null;
    }

    const linkId = buildLinkId(senderUid, recipientEmail);
    const linkRef = db.collection("trusted_contact_links").doc(linkId);
    const existingLinkSnap = await linkRef.get();
    const existingLink = existingLinkSnap.exists ? existingLinkSnap.data() : null;

    if (!recipientRecord) {
      const status = existingLink?.status === "INVITATION_PENDING" ? "INVITATION_PENDING" : "INVITATION_NEEDED";
      const detail =
        "This person hasn't joined SoloSignal yet. Invite them to install the app and complete setup, or choose another trusted contact who is already registered.";
      return res.status(200).json({
        ok: true,
        registered: false,
        status,
        statusDetail: detail,
        deviceCount: 0,
        notificationsEnabled: false
      });
    }

    const devicesSnap = await db
      .collection("users")
      .doc(recipientRecord.uid)
      .collection("devices")
      .get();
    const deviceCount = devicesSnap.size;
    const notificationsEnabled = devicesSnap.docs.some(d => d.data()?.notificationsEnabled === true);
    const isSelfContact = recipientRecord.uid === senderUid;
    const accepted = isSelfContact || existingLink?.recipientAccepted === true;

    let status = "REGISTERED";
    let statusDetail = "This person is registered on SoloSignal. Request permission to add them as your trusted contact.";

    if (accepted) {
      if (deviceCount === 0) {
        status = "DEVICE_NOT_REGISTERED";
        statusDetail = "Contact accepted, but their phone has not completed FCM device registration yet.";
      } else if (!notificationsEnabled) {
        status = "NOTIFICATIONS_DISABLED";
        statusDetail = "Contact's device is registered, but Android emergency notifications are disabled on their phone.";
      } else {
        status = "READY_FOR_ALERTS";
        statusDetail = "Authorized trusted contact with a registered device and emergency notifications enabled.";
      }
    } else if (sendContactRequest || existingLink?.status === "CONTACT_REQUEST_PENDING") {
      status = "CONTACT_REQUEST_PENDING";
      statusDetail = `Contact request sent to ${recipientName} (${recipientEmail}). Waiting for them to accept on their phone.`;
    }

    return res.status(200).json({
      ok: true,
      registered: true,
      accepted,
      status,
      statusDetail,
      deviceCount,
      notificationsEnabled
    });
  } catch (err) {
    console.error("verifyOrRequestTrustedContact error:", err);
    return res.status(500).json({ ok: false, error: err.message || "Verification failed." });
  }
});

/**
 * Formats plain-text and HTML email bodies for a confirmed emergency or explicit test alert.
 */
function buildEmergencyEmailContent({
  isTestAlert,
  senderName,
  senderEmail,
  recipientName,
  recipientEmail,
  subjectLine,
  bodyText,
  triggerReason,
  timestampFormatted,
  locationSummary,
  googleMapsUrl,
  latitude,
  longitude,
  audioStatusNote,
  audioDownloadUrl,
  audioDurationSeconds
}) {
  const safeSender = senderName || senderEmail || "SoloSignal User";
  const safeRecipient = recipientName || "Trusted Contact";
  const timeStr = timestampFormatted || new Date().toUTCString();

  const resolvedSubject =
    subjectLine ||
    (isTestAlert
      ? `[TEST — NOT AN EMERGENCY] SoloSignal test check-in for ${safeRecipient}`
      : `SOS ALERT — Possible Fall Detected (${safeSender})`);

  const resolvedAudioSummary =
    audioStatusNote ||
    (audioDownloadUrl
      ? `Attached / Available (${audioDurationSeconds ? `${Number(audioDurationSeconds).toFixed(1)}s ` : ""}recording) — ${audioDownloadUrl}`
      : "Unavailable or disabled by user");

  const plainText =
    bodyText ||
    [
      isTestAlert
        ? "TEST — NOT AN EMERGENCY"
        : "SOS ALERT — Possible Fall Detected",
      "",
      `Hello ${safeRecipient},`,
      "",
      isTestAlert
        ? `This is a user-initiated test email from SoloSignal AI configured by ${safeSender} (${senderEmail || "verified user"}).`
        : `SoloSignal AI has detected a possible fall involving the user configured for this alert (${safeSender}).\nThe user did not respond to the in-app confirmation prompt before the countdown expired.`,
      "",
      `Outcome: ${triggerReason || "Confirmed emergency / possible-fall check-in"}`,
      `Alert time: ${timeStr}`,
      `Location: ${locationSummary || "Location unavailable"}`,
      googleMapsUrl ? `Google Maps Link: ${googleMapsUrl}` : "",
      `Audio recording: ${resolvedAudioSummary}`,
      audioDownloadUrl ? `Audio Download Link: ${audioDownloadUrl}` : "",
      "",
      isTestAlert
        ? "No emergency has occurred. This confirms your email address receives SoloSignal alerts."
        : `Please try to contact the user (${safeSender}) and assess whether assistance is needed.\n\nThis is an automated alert generated by a fall-detection prototype. The detection may be incorrect, and the alert does not establish that an injury has occurred.`
    ]
      .filter(Boolean)
      .join("\n");

  const badgeColor = isTestAlert ? "#0EA5E9" : "#DC2626";
  const badgeText = isTestAlert
    ? "TEST ALERT — NOT AN EMERGENCY"
    : "SOS ALERT — POSSIBLE FALL DETECTED";

  const mapsButtonHtml = googleMapsUrl
    ? `<p style="margin: 14px 0;">
         <a href="${escapeHtml(googleMapsUrl)}"
            style="background-color: #141418; color: #BAFFD8; padding: 12px 20px; border-radius: 999px; text-decoration: none; font-weight: 700; display: inline-block;">
           Open GPS Location in Google Maps
         </a>
       </p>`
    : "";

  const audioButtonHtml = audioDownloadUrl
    ? `<p style="margin: 14px 0;">
         <a href="${escapeHtml(audioDownloadUrl)}"
            style="background-color: #DC2626; color: #FFFFFF; padding: 12px 20px; border-radius: 999px; text-decoration: none; font-weight: 700; display: inline-block;">
           Listen to 15s SOS Audio Recording
         </a>
       </p>`
    : "";

  const html = `
    <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 580px; margin: 0 auto; padding: 24px; background-color: #F6F4FA; border-radius: 20px; color: #141418;">
      <div style="background-color: ${badgeColor}; color: #FFFFFF; display: inline-block; padding: 6px 14px; border-radius: 999px; font-size: 12px; font-weight: 700; letter-spacing: 0.5px;">
        ${escapeHtml(badgeText)}
      </div>
      <h2 style="margin: 16px 0 8px 0; font-size: 22px; color: #141418;">
        ${escapeHtml(resolvedSubject)}
      </h2>
      <p style="font-size: 15px; line-height: 1.5; color: #2B2B33;">
        Hello <strong>${escapeHtml(safeRecipient)}</strong> (${escapeHtml(recipientEmail)}),
      </p>
      <div style="background-color: #FFFFFF; border-radius: 16px; padding: 16px; margin: 16px 0; border: 1px solid #E2DFEC;">
        <p style="margin: 6px 0;"><strong>Person monitored:</strong> ${escapeHtml(safeSender)} ${senderEmail ? `(${escapeHtml(senderEmail)})` : ""}</p>
        <p style="margin: 6px 0;"><strong>Event / Trigger:</strong> ${escapeHtml(triggerReason || "Possible fall safety check-in")}</p>
        <p style="margin: 6px 0;"><strong>Alert time:</strong> ${escapeHtml(timeStr)}</p>
        <p style="margin: 6px 0;"><strong>GPS Location:</strong> ${escapeHtml(locationSummary || "Location unavailable")}</p>
        <p style="margin: 6px 0;"><strong>Audio recording:</strong> ${escapeHtml(resolvedAudioSummary)}</p>
        ${latitude != null && longitude != null ? `<p style="margin: 6px 0;"><strong>Coordinates:</strong> ${escapeHtml(`${latitude}, ${longitude}`)}</p>` : ""}
        ${mapsButtonHtml}
        ${audioButtonHtml}
      </div>
      <pre style="white-space: pre-wrap; font-family: inherit; font-size: 14px; color: #2B2B33; background: #FFFFFF; padding: 14px; border-radius: 12px; border: 1px solid #E2DFEC;">${escapeHtml(plainText)}</pre>
      <p style="font-size: 12px; color: #646474; margin-top: 16px;">
        This is an automated alert generated by a fall-detection prototype. The detection may be incorrect, and the alert does not establish that an injury has occurred.
      </p>
    </div>
  `;

  return { subject: resolvedSubject, text: plainText, html };
}

/**
 * Generates a time-limited signed URL for a Firebase Storage object if storageObjectPath is provided
 * and no audioDownloadUrl was passed.
 */
async function resolveProtectedAudioUrl({ senderUid, storageObjectPath, existingDownloadUrl }) {
  if (existingDownloadUrl && typeof existingDownloadUrl === "string" && existingDownloadUrl.startsWith("https://")) {
    return existingDownloadUrl;
  }
  if (!storageObjectPath || typeof storageObjectPath !== "string") {
    return null;
  }
  const cleanPath = storageObjectPath.trim();
  if (!cleanPath.startsWith(`sos_audio/${senderUid}/`)) {
    return null;
  }
  try {
    const bucket = admin.storage().bucket();
    const file = bucket.file(cleanPath);
    const [exists] = await file.exists();
    if (!exists) return null;
    const [signedUrl] = await file.getSignedUrl({
      action: "read",
      expires: Date.now() + 24 * 60 * 60 * 1000 // 24 hours
    });
    return signedUrl;
  } catch (err) {
    console.warn("Could not generate signed URL for SOS audio:", err.message);
    return null;
  }
}

/**
 * Dispatches an email via SendGrid (when SENDGRID_API_KEY is configured in server environment)
 * or Google's official Gmail API. Supports optional base64 audio attachment.
 */
async function dispatchEmailViaGmailApi({
  accessToken,
  senderEmail,
  toEmail,
  replyToEmail,
  subject,
  text,
  html,
  audioAttachmentBase64,
  audioMimeType,
  audioFileName
}) {
  const sendGridApiKey = (process.env.SENDGRID_API_KEY || "").trim();
  const sendGridFromEmail = (process.env.SENDGRID_FROM_EMAIL || senderEmail || "alerts@solosignal.app").trim();

  if (sendGridApiKey && !sendGridApiKey.startsWith("YOUR_")) {
    const sgPayload = {
      personalizations: [{ to: [{ email: toEmail }] }],
      from: { email: sendGridFromEmail, name: "SoloSignal AI Emergency" },
      subject,
      content: [
        { type: "text/plain", value: text },
        { type: "text/html", value: html }
      ]
    };
    if (replyToEmail && isValidEmailAddress(replyToEmail)) {
      sgPayload.reply_to = { email: replyToEmail };
    }
    if (audioAttachmentBase64 && typeof audioAttachmentBase64 === "string") {
      sgPayload.attachments = [
        {
          content: audioAttachmentBase64,
          filename: audioFileName || "sos_recording.m4a",
          type: audioMimeType || "audio/mp4",
          disposition: "attachment"
        }
      ];
    }

    const sgRes = await fetch("https://api.sendgrid.com/v3/mail/send", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${sendGridApiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(sgPayload)
    });

    if (sgRes.ok) {
      const sgMessageId = sgRes.headers.get("x-message-id") || `sg_${Date.now()}`;
      return {
        providerName: "SendGrid",
        messageId: String(sgMessageId)
      };
    }
  }

  const token = (accessToken || process.env.GMAIL_ACCESS_TOKEN || "").trim();
  if (!token) {
    throw new Error(
      "Gmail API sending authorization required: Please authorize your Google account to send emergency emails (scope: https://www.googleapis.com/auth/gmail.send)."
    );
  }

  const safeSender = senderEmail || "me";
  const mixedBoundary = "__mixed_" + Date.now().toString(16) + "__";
  const altBoundary = "__boundary_" + Date.now().toString(16) + "__";
  const utf8Subject = `=?utf-8?B?${Buffer.from(subject).toString("base64")}?=`;
  const hasAttachment = Boolean(audioAttachmentBase64 && typeof audioAttachmentBase64 === "string");
  const safeFileName = audioFileName || "sos_recording.m4a";
  const safeMimeType = audioMimeType || "audio/mp4";

  const messageLines = [
    `From: ${safeSender}`,
    `To: ${toEmail}`,
    replyToEmail ? `Reply-To: ${replyToEmail}` : null,
    `Subject: ${utf8Subject}`,
    "MIME-Version: 1.0",
    hasAttachment
      ? `Content-Type: multipart/mixed; boundary="${mixedBoundary}"`
      : `Content-Type: multipart/alternative; boundary="${altBoundary}"`,
    "",
    hasAttachment ? `--${mixedBoundary}` : null,
    hasAttachment ? `Content-Type: multipart/alternative; boundary="${altBoundary}"` : null,
    hasAttachment ? "" : null,
    `--${altBoundary}`,
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: base64",
    "",
    Buffer.from(text).toString("base64"),
    "",
    `--${altBoundary}`,
    "Content-Type: text/html; charset=utf-8",
    "Content-Transfer-Encoding: base64",
    "",
    Buffer.from(html).toString("base64"),
    "",
    `--${altBoundary}--`,
    hasAttachment ? "" : null,
    hasAttachment ? `--${mixedBoundary}` : null,
    hasAttachment ? `Content-Type: ${safeMimeType}; name="${safeFileName}"` : null,
    hasAttachment ? `Content-Disposition: attachment; filename="${safeFileName}"` : null,
    hasAttachment ? "Content-Transfer-Encoding: base64" : null,
    hasAttachment ? "" : null,
    hasAttachment ? audioAttachmentBase64 : null,
    hasAttachment ? "" : null,
    hasAttachment ? `--${mixedBoundary}--` : null
  ]
    .filter(line => line !== null)
    .join("\r\n");

  const base64UrlMessage = Buffer.from(messageLines)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

  const response = await fetch("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ raw: base64UrlMessage })
  });

  const rawBody = await response.text();
  let parsed = {};
  try {
    parsed = JSON.parse(rawBody);
  } catch (_) {}

  if (!response.ok || !parsed.id) {
    const errDetail =
      (parsed.error && parsed.error.message) ||
      `Gmail API returned HTTP ${response.status}: ${rawBody.slice(0, 200)}`;
    throw new Error(`Gmail API rejected email send: ${errDetail}`);
  }

  return {
    providerName: "Gmail API",
    messageId: String(parsed.id)
  };
}

/**
 * HTTPS Cloud Function: `sendSoloSignalEmergencyEmail`
 */
exports.sendSoloSignalEmergencyEmail = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({
      emailSent: false,
      error: "Method Not Allowed. Use POST."
    });
  }

  try {
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      return res.status(401).json({
        emailSent: false,
        error: "Missing Firebase Authentication Bearer token."
      });
    }

    const idToken = authHeader.substring("Bearer ".length).trim();
    const decodedToken = await admin.auth().verifyIdToken(idToken);
    const uid = decodedToken.uid;

    const {
      alertId,
      isTestAlert,
      senderName,
      recipientName,
      recipientEmail: bodyRecipientEmail,
      subjectLine,
      bodyText,
      triggerReason,
      timestampFormatted,
      locationSummary,
      googleMapsUrl,
      latitude,
      longitude,
      storageObjectPath,
      audioDownloadUrl: bodyAudioDownloadUrl,
      audioDurationSeconds,
      audioStatusNote,
      audioAttachmentBase64,
      audioMimeType,
      audioFileName,
      gmailAccessToken: bodyGmailAccessToken
    } = req.body || {};

    const gmailAccessToken = (
      bodyGmailAccessToken ||
      req.headers["x-gmail-access-token"] ||
      process.env.GMAIL_ACCESS_TOKEN ||
      ""
    ).trim();

    const db = admin.firestore();
    let alertRef = null;
    let existingAlert = null;

    if (alertId && typeof alertId === "string") {
      alertRef = db.collection("emergency_alerts").doc(alertId.trim());
      const snap = await alertRef.get();
      if (snap.exists) {
        existingAlert = snap.data();
        if (existingAlert.senderUid && existingAlert.senderUid !== uid) {
          return res.status(403).json({
            emailSent: false,
            error: "Unauthorized: alertId belongs to a different user."
          });
        }
        if (
          existingAlert.emailDeliveryStatus === "EMAIL_SENT" &&
          existingAlert.emailProviderMessageId
        ) {
          return res.status(200).json({
            emailSent: true,
            duplicatePrevented: true,
            providerName: existingAlert.emailProvider || "Gmail API",
            messageId: existingAlert.emailProviderMessageId,
            recipientEmail: existingAlert.recipientEmail,
            detail: `Email already sent to ${existingAlert.recipientEmail} via ${existingAlert.emailProvider || "Gmail API"} (ID: ${existingAlert.emailProviderMessageId}).`
          });
        }
      }
    }

    let resolvedRecipientEmail = (
      (existingAlert && existingAlert.recipientEmail) ||
      bodyRecipientEmail ||
      ""
    )
      .trim()
      .toLowerCase();

    let resolvedRecipientName =
      (existingAlert && existingAlert.recipientName) ||
      recipientName ||
      "Trusted Contact";

    if (!resolvedRecipientEmail) {
      const userSnap = await db.collection("users").doc(uid).get();
      if (userSnap.exists) {
        const userData = userSnap.data() || {};
        resolvedRecipientEmail = (userData.trustedContactEmail || "")
          .trim()
          .toLowerCase();
        resolvedRecipientName =
          userData.trustedContactName || resolvedRecipientName;
      }
    }

    if (!isValidEmailAddress(resolvedRecipientEmail)) {
      const invalidMsg =
        "Trusted contact does not have a valid email address configured.";
      if (alertRef && existingAlert) {
        await alertRef.update({
          deliveryState: "EMAIL_FAILED",
          emailDeliveryStatus: "EMAIL_FAILED",
          emailStatus: "EMAIL_FAILED",
          emailError: invalidMsg,
          failureReason: invalidMsg,
          deliveryStatusDetail: `Email failed: ${invalidMsg}`,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      }
      return res.status(400).json({
        emailSent: false,
        error: invalidMsg
      });
    }

    const resolvedSenderName =
      (existingAlert && existingAlert.senderName) ||
      senderName ||
      decodedToken.name ||
      decodedToken.email ||
      "SoloSignal User";
    const resolvedSenderEmail =
      (existingAlert && existingAlert.senderEmail) ||
      decodedToken.email ||
      "";

    const resolvedAudioUrl = await resolveProtectedAudioUrl({
      senderUid: uid,
      storageObjectPath: (existingAlert && existingAlert.storageObjectPath) || storageObjectPath,
      existingDownloadUrl: (existingAlert && existingAlert.audioDownloadUrl) || bodyAudioDownloadUrl
    });

    // Also dispatch real FCM push notifications to all registered devices of the authorized recipient
    const fcmDispatch = await dispatchEmergencyFcmToRecipientDevices({
      db,
      alertId: alertId || "",
      senderUid: uid,
      senderName: resolvedSenderName,
      senderEmail: resolvedSenderEmail,
      recipientName: resolvedRecipientName,
      recipientEmail: resolvedRecipientEmail,
      isTestAlert: Boolean(existingAlert ? existingAlert.isTestAlert : isTestAlert),
      subjectLine: (existingAlert && existingAlert.subjectLine) || subjectLine,
      bodyText: (existingAlert && existingAlert.bodyText) || bodyText,
      triggerReason: (existingAlert && existingAlert.triggerReason) || triggerReason,
      locationSummary: (existingAlert && existingAlert.locationSummary) || locationSummary,
      googleMapsUrl: (existingAlert && existingAlert.googleMapsUrl) || googleMapsUrl,
      latitude: existingAlert ? existingAlert.latitude : latitude,
      longitude: existingAlert ? existingAlert.longitude : longitude
    });

    const emailContent = buildEmergencyEmailContent({
      isTestAlert: Boolean(
        existingAlert ? existingAlert.isTestAlert : isTestAlert
      ),
      senderName: resolvedSenderName,
      senderEmail: resolvedSenderEmail,
      recipientName: resolvedRecipientName,
      recipientEmail: resolvedRecipientEmail,
      subjectLine: (existingAlert && existingAlert.subjectLine) || subjectLine,
      bodyText: (existingAlert && existingAlert.bodyText) || bodyText,
      triggerReason:
        (existingAlert && existingAlert.triggerReason) || triggerReason,
      timestampFormatted,
      locationSummary:
        (existingAlert && existingAlert.locationSummary) || locationSummary,
      googleMapsUrl:
        (existingAlert && existingAlert.googleMapsUrl) || googleMapsUrl,
      latitude: existingAlert ? existingAlert.latitude : latitude,
      longitude: existingAlert ? existingAlert.longitude : longitude,
      audioStatusNote,
      audioDownloadUrl: resolvedAudioUrl,
      audioDurationSeconds:
        (existingAlert && existingAlert.audioDurationSeconds) || audioDurationSeconds
    });

    const providerResult = await dispatchEmailViaGmailApi({
      accessToken: gmailAccessToken,
      senderEmail: resolvedSenderEmail || "me",
      toEmail: resolvedRecipientEmail,
      replyToEmail: decodedToken.email || undefined,
      subject: emailContent.subject,
      text: emailContent.text,
      html: emailContent.html,
      audioAttachmentBase64,
      audioMimeType,
      audioFileName
    });

    const fcmSummary =
      fcmDispatch.deliveredCount > 0
        ? ` FCM push sent to ${fcmDispatch.deliveredCount} registered device(s).`
        : "";
    const detailMsg = `Email sent to ${resolvedRecipientEmail} via ${providerResult.providerName} (ID: ${providerResult.messageId}).${fcmSummary}`;

    if (alertRef && existingAlert) {
      await alertRef.update({
        deliveryState: "EMAIL_SENT",
        emailDeliveryStatus: "EMAIL_SENT",
        emailStatus: "EMAIL_SENT",
        emailSentAt: admin.firestore.FieldValue.serverTimestamp(),
        emailProvider: providerResult.providerName,
        emailProviderMessageId: providerResult.messageId,
        emailError: null,
        failureReason: null,
        fcmDeliveryStatus: fcmDispatch.fcmStatus,
        fcmDeliveredCount: fcmDispatch.deliveredCount,
        providerMessageId: providerResult.messageId,
        deliveryStatusDetail: detailMsg,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    }

    return res.status(200).json({
      emailSent: true,
      providerName: providerResult.providerName,
      messageId: providerResult.messageId,
      fcmDeliveryStatus: fcmDispatch.fcmStatus,
      fcmDeliveredCount: fcmDispatch.deliveredCount,
      recipientEmail: resolvedRecipientEmail,
      detail: detailMsg
    });
  } catch (err) {
    console.error("SoloSignal emergency email delivery error:", err);
    const errMsg = err.message || "Failed to send emergency email.";

    const alertId = req.body && req.body.alertId;
    if (alertId && typeof alertId === "string") {
      try {
        await admin
          .firestore()
          .collection("emergency_alerts")
          .doc(alertId.trim())
          .update({
            deliveryState: "EMAIL_FAILED",
            emailDeliveryStatus: "EMAIL_FAILED",
            emailStatus: "EMAIL_FAILED",
            emailError: errMsg.slice(0, 580),
            failureReason: errMsg.slice(0, 580),
            deliveryStatusDetail: `Emergency record created, but email failed: ${errMsg}`.slice(
              0,
              580
            ),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
      } catch (_) {}
    }

    return res.status(502).json({
      emailSent: false,
      error: errMsg
    });
  }
});

/**
 * Firestore Trigger: Automatically processes new emergency_alerts/{alertId} documents
 * to dispatch FCM push notifications to authorized recipient devices and send email if needed.
 */
exports.onEmergencyAlertCreated = functions.firestore
  .document("emergency_alerts/{alertId}")
  .onCreate(async (snap, context) => {
    const alertData = snap.data() || {};
    const alertId = context.params.alertId;
    const db = admin.firestore();

    const recipientEmail = (alertData.recipientEmail || "").trim().toLowerCase();

    // 1. Always dispatch high-priority FCM push notifications to authorized recipient devices
    let fcmDispatch = { fcmStatus: "NO_REGISTERED_DEVICE", deliveredCount: 0, messageIds: [] };
    try {
      fcmDispatch = await dispatchEmergencyFcmToRecipientDevices({
        db,
        alertId,
        senderUid: alertData.senderUid,
        senderName: alertData.senderName,
        senderEmail: alertData.senderEmail,
        recipientName: alertData.recipientName,
        recipientEmail,
        isTestAlert: Boolean(alertData.isTestAlert),
        subjectLine: alertData.subjectLine,
        bodyText: alertData.bodyText,
        triggerReason: alertData.triggerReason,
        locationSummary: alertData.locationSummary,
        googleMapsUrl: alertData.googleMapsUrl,
        latitude: alertData.latitude,
        longitude: alertData.longitude
      });

      if (fcmDispatch.deliveredCount > 0) {
        await snap.ref.update({
          fcmDeliveryStatus: "SENT",
          fcmDeliveredCount: fcmDispatch.deliveredCount,
          providerMessageId: fcmDispatch.messageIds[0] || alertData.providerMessageId,
          deliveryState:
            alertData.deliveryState === "REQUEST_ACCEPTED"
              ? "SENT_BY_PROVIDER"
              : alertData.deliveryState,
          deliveryStatusDetail: `FCM emergency push notification sent to ${fcmDispatch.deliveredCount} registered device(s).`,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      }
    } catch (fcmErr) {
      console.warn(`FCM dispatch error for ${alertId}:`, fcmErr.message);
    }

    if (
      alertData.emailDeliveryStatus === "EMAIL_SENT" &&
      alertData.emailProviderMessageId
    ) {
      return null;
    }

    if (!isValidEmailAddress(recipientEmail)) {
      await snap.ref.update({
        deliveryState: "EMAIL_FAILED",
        emailDeliveryStatus: "EMAIL_FAILED",
        emailStatus: "EMAIL_FAILED",
        emailError: "Invalid or missing recipientEmail on emergency_alerts document.",
        failureReason: "Invalid or missing recipientEmail on emergency_alerts document.",
        deliveryStatusDetail:
          "Emergency record created, but recipient email address is missing or invalid.",
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return null;
    }

    try {
      const resolvedAudioUrl = await resolveProtectedAudioUrl({
        senderUid: alertData.senderUid,
        storageObjectPath: alertData.storageObjectPath,
        existingDownloadUrl: alertData.audioDownloadUrl
      });

      const emailContent = buildEmergencyEmailContent({
        isTestAlert: Boolean(alertData.isTestAlert),
        senderName: alertData.senderName,
        senderEmail: alertData.senderEmail,
        recipientName: alertData.recipientName,
        recipientEmail,
        subjectLine: alertData.subjectLine,
        bodyText: alertData.bodyText,
        triggerReason: alertData.triggerReason,
        timestampFormatted: new Date().toUTCString(),
        locationSummary: alertData.locationSummary,
        googleMapsUrl: alertData.googleMapsUrl,
        latitude: alertData.latitude,
        longitude: alertData.longitude,
        audioDownloadUrl: resolvedAudioUrl,
        audioDurationSeconds: alertData.audioDurationSeconds
      });

      const providerResult = await dispatchEmailViaGmailApi({
        accessToken: (alertData.gmailAccessToken || process.env.GMAIL_ACCESS_TOKEN || "").trim(),
        senderEmail: alertData.senderEmail || "me",
        toEmail: recipientEmail,
        replyToEmail: alertData.senderEmail || undefined,
        subject: emailContent.subject,
        text: emailContent.text,
        html: emailContent.html
      });

      const detailMsg = `Email sent to ${recipientEmail} via ${providerResult.providerName} (ID: ${providerResult.messageId}).`;
      await snap.ref.update({
        deliveryState: "EMAIL_SENT",
        emailDeliveryStatus: "EMAIL_SENT",
        emailStatus: "EMAIL_SENT",
        emailSentAt: admin.firestore.FieldValue.serverTimestamp(),
        emailProvider: providerResult.providerName,
        emailProviderMessageId: providerResult.messageId,
        emailError: null,
        failureReason: null,
        providerMessageId: providerResult.messageId,
        deliveryStatusDetail: detailMsg,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } catch (err) {
      console.error(`Auto email trigger failed for ${alertId}:`, err.message);
      if (fcmDispatch.deliveredCount === 0) {
        await snap.ref.update({
          deliveryState: "EMAIL_FAILED",
          emailDeliveryStatus: "EMAIL_FAILED",
          emailStatus: "EMAIL_FAILED",
          emailError: (err.message || "Email provider error").slice(0, 580),
          failureReason: (err.message || "Email provider error").slice(0, 580),
          deliveryStatusDetail: `Emergency record created, but email failed: ${err.message}`.slice(
            0,
            580
          ),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      }
    }
    return null;
  });

/**
 * HTTPS Cloud Function: Google Chat Space Alert Delivery
 */
exports.sendSoloSignalGoogleChatAlert = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({
      ok: false,
      delivered: false,
      error: "Method Not Allowed. Use POST."
    });
  }

  try {
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      return res.status(401).json({
        ok: false,
        delivered: false,
        error: "Missing Firebase Authentication Bearer token."
      });
    }

    const idToken = authHeader.substring("Bearer ".length).trim();
    const decodedToken = await admin.auth().verifyIdToken(idToken);

    const {
      action,
      messageType,
      spaceName,
      spaceDisplayLabel,
      senderName,
      userDisplayName,
      recipientEmail,
      alertId,
      subjectLine,
      messageText,
      text,
      triggerReason,
      detectedAtFormatted,
      location
    } = req.body || {};

    if (!spaceName || typeof spaceName !== "string" || !/^spaces\/[A-Za-z0-9_-]+$/.test(spaceName.trim())) {
      return res.status(400).json({
        ok: false,
        delivered: false,
        error: "Invalid Google Chat spaceName. Expected format: spaces/AAAAxxxxxxx"
      });
    }

    const normalizedSpace = spaceName.trim();
    const effectiveAction = action || messageType;
    const effectiveSender = senderName || userDisplayName || decodedToken.name || decodedToken.email || "SoloSignal User";

    let chatText = "";
    if (effectiveAction === "INITIAL_CONNECTION") {
      chatText = INITIAL_CONNECTION_MESSAGE;
    } else {
      chatText = messageText || text || [
        `*${subjectLine || "SoloSignal AI Alert"}*`,
        `User: ${effectiveSender}`,
        `Event: ${triggerReason || "Possible fall / assistance alert"}`,
        `Time: ${detectedAtFormatted || new Date().toISOString()}`,
        location && location.googleMapsUrl
          ? `GPS Location: ${location.latitude}, ${location.longitude} (±${Math.round(location.accuracyMeters || 0)}m)\nMap: ${location.googleMapsUrl}`
          : "GPS Location: Unavailable at the time of alert."
      ].join("\n");
    }

    const auth = new GoogleAuth({
      scopes: [CHAT_BOT_SCOPE]
    });
    const client = await auth.getClient();
    const chatApiUrl = `https://chat.googleapis.com/v1/${normalizedSpace}/messages`;

    const chatResponse = await client.request({
      url: chatApiUrl,
      method: "POST",
      data: {
        text: chatText
      }
    });

    const createdMessage = chatResponse.data || {};
    if (!createdMessage.name) {
      return res.status(502).json({
        ok: false,
        delivered: false,
        error: "Google Chat API did not return a created message resource name."
      });
    }

    if (alertId && typeof alertId === "string") {
      try {
        await admin
          .firestore()
          .collection("emergency_alerts")
          .doc(alertId)
          .update({
            deliveryState: "SENT_BY_PROVIDER",
            providerMessageId: createdMessage.name,
            deliveryStatusDetail: `Google Chat API confirmed delivery (${createdMessage.name}).`,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
      } catch (firestoreErr) {
        console.warn("Could not update emergency_alerts doc:", firestoreErr.message);
      }
    }

    let fcmDispatch = { deliveredCount: 0, messageIds: [] };
    if (recipientEmail && typeof recipientEmail === "string") {
      try {
        fcmDispatch = await dispatchEmergencyFcmToRecipientDevices({
          db: admin.firestore(),
          alertId: alertId || "",
          senderUid: decodedToken.uid,
          senderName: effectiveSender,
          senderEmail: decodedToken.email || "",
          recipientName: "Trusted Contact",
          recipientEmail,
          isTestAlert: false,
          subjectLine: subjectLine || `SoloSignal Emergency Alert: ${effectiveSender}`,
          bodyText: chatText,
          triggerReason: triggerReason || "Possible fall / assistance alert",
          locationSummary:
            location && location.googleMapsUrl
              ? `${location.latitude}, ${location.longitude}`
              : "Location unavailable",
          googleMapsUrl: (location && location.googleMapsUrl) || "",
          latitude: location ? location.latitude : null,
          longitude: location ? location.longitude : null
        });
      } catch (fcmErr) {
        console.warn("Optional FCM push dispatch skipped:", fcmErr.message);
      }
    }

    return res.status(200).json({
      ok: true,
      delivered: true,
      spaceName: normalizedSpace,
      spaceDisplayLabel: spaceDisplayLabel || normalizedSpace,
      chatMessageName: createdMessage.name,
      messageId: createdMessage.name,
      fcmMessageId: fcmDispatch.messageIds[0] || null,
      message:
        effectiveAction === "INITIAL_CONNECTION"
          ? "Connected to Google Chat. Initial connection message delivered."
          : `Message delivered to ${normalizedSpace} (${createdMessage.name}).`
    });
  } catch (err) {
    console.error("SoloSignal Google Chat delivery error:", err);
    return res.status(500).json({
      ok: false,
      delivered: false,
      error: err.message || "Failed to deliver message to Google Chat space."
    });
  }
});
