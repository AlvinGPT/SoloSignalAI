/**
 * Local Firestore Security Rules Test Suite for SoloSignal AI
 * Run via: firebase emulators:exec --only firestore "node --test firestore.test.js"
 */
const { describe, it, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  Timestamp,
} = require('firebase/firestore');

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-solosignal-test',
    firestore: {
      rules: fs.readFileSync('firestore.rules', 'utf8'),
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

describe('SoloSignal AI Firestore Security Rules', () => {
  it('denies unauthenticated users from reading or writing /users/{uid}', async () => {
    const unauthDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(unauthDb, 'users/alice-123')));
    await assertFails(
      setDoc(doc(unauthDb, 'users/alice-123'), {
        userId: 'alice-123',
        displayName: 'Alice',
        email: 'alice@example.com',
        photoUrl: '',
        trustedContactName: 'Bob',
        trustedContactEmail: 'bob@example.com',
        trustedContactPhone: '+15550199',
        trustedContactRelationship: 'Partner',
        preferredChannel: 'SOLOSIGNAL_POPUP',
        phonePopUpAlertsEnabled: true,
        updatedAt: Timestamp.now(),
      })
    );
  });

  it('allows authenticated owner to create and update their own UserProfile and prevents public directory lookups', async () => {
    const aliceDb = testEnv.authenticatedContext('alice-123', {
      email: 'alice@example.com',
    }).firestore();
    const strangerDb = testEnv.authenticatedContext('eve-999', {
      email: 'eve@example.com',
    }).firestore();

    const ref = doc(aliceDb, 'users/alice-123');
    await assertSucceeds(
      setDoc(ref, {
        userId: 'alice-123',
        displayName: 'Alice Smith',
        email: 'alice@example.com',
        photoUrl: 'https://example.com/photo.png',
        trustedContactName: 'Sam Rivera',
        trustedContactEmail: 'sam@example.com',
        trustedContactPhone: '+15550100',
        trustedContactRelationship: 'Family',
        preferredChannel: 'SOLOSIGNAL_POPUP',
        popUpAlertsEnabled: true,
        updatedAt: Timestamp.now(),
      })
    );

    const snap = await getDoc(ref);
    assert.equal(snap.exists(), true);

    // Other authenticated users cannot read Alice's profile (no public user directory)
    await assertFails(getDoc(doc(strangerDb, 'users/alice-123')));
  });

  it('protects recipient FCM device tokens in /users/{uid}/devices/{deviceId} from other users', async () => {
    const samDb = testEnv.authenticatedContext('sam-456', {
      email: 'sam@example.com',
    }).firestore();
    const aliceDb = testEnv.authenticatedContext('alice-123', {
      email: 'alice@example.com',
    }).firestore();

    const now = Timestamp.now();
    const samDeviceRef = doc(samDb, 'users/sam-456/devices/pixel_9_pro');
    await assertSucceeds(
      setDoc(samDeviceRef, {
        deviceId: 'pixel_9_pro',
        userId: 'sam-456',
        fcmToken: 'fcm_token_secret_sam_device_1234567890',
        deviceName: 'Google Pixel 9 Pro',
        notificationsEnabled: true,
        appVersion: '1.0',
        sdkInt: 36,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Sam can read and refresh his own token
    await assertSucceeds(getDoc(samDeviceRef));
    await assertSucceeds(
      updateDoc(samDeviceRef, {
        fcmToken: 'fcm_token_refreshed_9876543210',
        updatedAt: Timestamp.now(),
      })
    );

    // Alice (even as a sender) cannot read Sam's raw FCM token
    await assertFails(getDoc(doc(aliceDb, 'users/sam-456/devices/pixel_9_pro')));
  });

  it('enforces privacy-preserving trusted_contact_links creation, recipient acceptance, and revocation', async () => {
    const senderDb = testEnv.authenticatedContext('alice-123', {
      email: 'alice@example.com',
    }).firestore();
    const recipientDb = testEnv.authenticatedContext('sam-456', {
      email: 'sam@example.com',
    }).firestore();
    const strangerDb = testEnv.authenticatedContext('eve-999', {
      email: 'eve@example.com',
    }).firestore();

    const now = Timestamp.now();
    const linkId = 'link_alice_123_sam';
    const senderLinkRef = doc(senderDb, `trusted_contact_links/${linkId}`);

    await assertSucceeds(
      setDoc(senderLinkRef, {
        linkId,
        senderUid: 'alice-123',
        senderName: 'Alice Smith',
        senderEmail: 'alice@example.com',
        recipientName: 'Sam Rivera',
        recipientEmail: 'sam@example.com',
        recipientPhone: '+15550100',
        relationship: 'Family',
        status: 'CONTACT_REQUEST_PENDING',
        statusDetail: 'Contact request sent to sam@example.com.',
        recipientHasRegisteredAccount: true,
        recipientAccepted: false,
        recipientRegisteredDeviceCount: 0,
        recipientNotificationsEnabled: false,
        shareLocation: true,
        popUpAlertsEnabled: true,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Stranger cannot read or tamper with the link
    await assertFails(getDoc(doc(strangerDb, `trusted_contact_links/${linkId}`)));

    // Recipient matching recipientEmail can read and accept the link with device readiness status
    const recipientLinkRef = doc(recipientDb, `trusted_contact_links/${linkId}`);
    await assertSucceeds(getDoc(recipientLinkRef));
    await assertSucceeds(
      updateDoc(recipientLinkRef, {
        recipientUid: 'sam-456',
        recipientHasRegisteredAccount: true,
        recipientAccepted: true,
        recipientRegisteredDeviceCount: 1,
        recipientNotificationsEnabled: true,
        status: 'READY_FOR_ALERTS',
        statusDetail: 'Sam Rivera accepted and registered 1 device with emergency notifications enabled.',
        updatedAt: Timestamp.now(),
      })
    );

    // Sender can remove the contact and revoke alert authorization
    await assertSucceeds(deleteDoc(senderLinkRef));
  });

  it('allows sender to create an emergency_alerts record with PENDING emailDeliveryStatus and update to EMAIL_SENT or EMAIL_FAILED', async () => {
    const senderDb = testEnv.authenticatedContext('alice-123', {
      email: 'alice@example.com',
    }).firestore();
    const recipientDb = testEnv.authenticatedContext('sam-456', {
      email: 'sam@example.com',
    }).firestore();
    const strangerDb = testEnv.authenticatedContext('eve-999', {
      email: 'eve@example.com',
    }).firestore();

    const alertRefSender = doc(senderDb, 'emergency_alerts/alert-001');
    const now = Timestamp.now();

    await assertSucceeds(
      setDoc(alertRefSender, {
        alertId: 'alert-001',
        senderUid: 'alice-123',
        senderName: 'Alice Smith',
        senderEmail: 'alice@example.com',
        recipientName: 'Sam Rivera',
        recipientEmail: 'sam@example.com',
        recipientPhone: '+15550100',
        isTestAlert: false,
        subjectLine: 'SoloSignal Emergency Alert: Alice Smith may need help',
        bodyText: 'Possible fall detected with no check-in response.',
        triggerReason: 'Confirmed emergency',
        deliveryState: 'REQUEST_ACCEPTED',
        deliveryStatusDetail: 'Emergency request recorded; sending email to Sam Rivera...',
        providerMessageId: 'firestore:emergency_alerts/alert-001',
        emailDeliveryStatus: 'PENDING',
        emailProvider: 'pending',
        emailProviderMessageId: '',
        emailError: '',
        googleMapsUrl: 'https://www.google.com/maps?q=37.7749,-122.4194',
        locationSummary: '37.77490, -122.41940 (±5m)',
        createdAt: now,
        updatedAt: now,
      })
    );

    // Sender can record real EmailSent status with provider receipt ID
    await assertSucceeds(
      updateDoc(alertRefSender, {
        deliveryState: 'EMAIL_SENT',
        deliveryStatusDetail: 'Emergency email sent via Gmail API to sam@example.com.',
        providerMessageId: 'Gmail API:gmail_msg_123456',
        emailDeliveryStatus: 'SENT',
        emailProvider: 'Gmail API',
        emailProviderMessageId: 'gmail_msg_123456',
        emailError: '',
        updatedAt: Timestamp.now(),
      })
    );

    // Recipient matching recipientEmail can read and acknowledge delivery
    const alertRefRecipient = doc(recipientDb, 'emergency_alerts/alert-001');
    const recipientSnap = await assertSucceeds(getDoc(alertRefRecipient));
    assert.equal(recipientSnap.exists(), true);

    await assertSucceeds(
      updateDoc(alertRefRecipient, {
        deliveryState: 'DELIVERED_TO_RECIPIENT',
        deliveryStatusDetail: 'Recipient phone pop-up alert displayed on device.',
        updatedAt: Timestamp.now(),
      })
    );

    // Recipient acknowledgement can also cancel the 15-second video-call fallback
    await assertSucceeds(
      updateDoc(alertRefRecipient, {
        deliveryState: 'ACKNOWLEDGED_BY_RECIPIENT',
        deliveryStatusDetail: "Sam Rivera tapped 'I'm checking on them'.",
        escalationStatus: 'CANCELLED_BY_ACK',
        updatedAt: Timestamp.now(),
      })
    );

    // Unrelated user cannot read or update the alert
    const alertRefStranger = doc(strangerDb, 'emergency_alerts/alert-001');
    await assertFails(getDoc(alertRefStranger));
    await assertFails(
      updateDoc(alertRefStranger, {
        deliveryState: 'ACKNOWLEDGED_BY_RECIPIENT',
        updatedAt: Timestamp.now(),
      })
    );

    // Emergency alerts are immutable audit records (delete forbidden)
    await assertFails(deleteDoc(alertRefSender));
  });
});

