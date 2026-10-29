# SoloSignal AI --- Working and Feature Specification

## 1. Purpose

SoloSignal is an Android prototype that looks for motion patterns that
may be consistent with a fall, asks the user to confirm they are okay,
and---if the user does not respond---attempts to notify a configured
trusted contact.

This is a prototype, not a certified medical device or a guaranteed
emergency-response system.

## 2. MVP scope

Build these features first, in this order:

1.  A polished Compose UI following `design.md`.
2.  A real accelerometer availability check.
3.  Start/stop monitoring controlled by the user.
4.  A sensor data pipeline that records timestamps and acceleration
    samples locally while monitoring is active.
5.  A transparent candidate-event detector using simple thresholds and a
    sequence of motion features.
6.  A confirmation screen with a cancelable countdown.
7.  A local event history.
8.  A test-alert workflow.
9.  Trusted-contact configuration.
10. Optional real email/Chat integration only after the local workflow
    works.

Do not claim to have a trained AI classifier unless a real model has
been supplied and integrated. For the first prototype, call the detector
"motion-pattern screening" or "candidate fall detector"; the Gemini API
should not be called for every sensor sample.

## 3. User workflow

### First launch

1.  Show onboarding and safety disclaimer.
2.  Explain why motion-sensor access and notifications are requested.
3.  Let the user configure a trusted contact.
4.  Run a clearly labelled test alert if an alert channel is configured.
5.  Monitoring remains off until the user explicitly starts it.

### Monitoring

1.  User taps **Start monitoring**.
2.  App checks that the accelerometer is available and necessary
    permissions are granted.
3.  Show a persistent, truthful monitoring status.
4.  Collect sensor readings only while monitoring is enabled.
5.  Calculate features such as acceleration magnitude, change over time,
    orientation change when a rotation sensor is available, and
    post-event movement/inactivity.
6.  If a candidate event is detected, open the confirmation flow.

### Confirmation

1.  Show "Are you okay?" and a configurable countdown (default 20
    seconds).
2.  **I'm okay** cancels the alert and logs "Cancelled by user".
3.  **Send help alert now** attempts notification immediately.
4.  If the countdown expires, attempt the configured alert.
5.  Display "Alert sent" only after the provider/backend confirms
    success; otherwise display the actual failure and a fallback
    instruction.

### Contact alert

A notification should contain only user-approved details, such as: - The
user's configured display name. - "SoloSignal detected a possible fall
and the user did not respond." - Event timestamp. - Optional location
only if separately enabled and available with the required
permissions. - A reminder to try calling the user and contact local
emergency services if appropriate.

Never label a suspected fall as a confirmed fall.

## 4. Suggested architecture

-   **UI:** Kotlin + Jetpack Compose, Material 3.
-   **Navigation:** Navigation Compose or a simple state-driven
    navigation approach.
-   **Sensors:** Android `SensorManager` and `SensorEventListener`; use
    accelerometer first. Rotation/orientation features are optional.
-   **Domain layer:** `MotionSample`, `MotionFeatures`,
    `CandidateEvent`, `AlertAttempt`, `TrustedContact`.
-   **Detector:** deterministic, testable Kotlin class, separate from
    UI.
-   **Persistence:** DataStore for settings; Room or a small local store
    for event history if available.
-   **Background monitoring:** Android foreground service with a
    persistent notification when continuous monitoring is implemented.
    Respect Android OS restrictions and request user consent.
-   **Notifications:** Android notification channel for local check-in
    alerts.
-   **Remote messaging:** secure backend (for example, Firebase Cloud
    Functions or another trusted server) for email or Google Chat API
    calls. Keep credentials on the server, never in the APK.
-   **AI:** optional later-stage classifier trained/validated on
    suitable fall/non-fall sensor data. Gemini can help explain setup or
    analyze a user-submitted report, but should not be the real-time
    sensor classifier by default.

## 5. Physics and signal-processing logic

Class 11 connections: - Chapter 2: velocity and acceleration as rates of
change. - Chapter 3: vector components and resultant magnitude. -
Chapter 4: Newton's laws and how forces cause changes in motion.

For accelerometer components (a_x,a_y,a_z), a basic vector magnitude is:

`aMagnitude = sqrt(ax*ax + ay*ay + az*az)`

Sensor values include gravity depending on sensor type and Android
implementation. Document whether the detector uses raw accelerometer
values or gravity-removed linear acceleration. Do not mix them without
accounting for the difference.

A candidate event may use a time window containing a sudden acceleration
change, a subsequent orientation/motion change, and a period of reduced
movement. Thresholds must be configurable and treated as heuristic
starting points---not validated clinical thresholds.

Important: a phone dropped onto a surface can produce a large
acceleration spike. A single spike is not enough to identify a human
fall. The app should look at a sequence of features and still expect
false positives and false negatives.

## 6. Alert integration options

### Local-only prototype

-   Demonstrate the complete confirmation/countdown workflow on one
    device.
-   Record an event locally.
-   Use a clearly labelled test notification.
-   This is the easiest and safest first milestone.

### Email

-   Use a secure server-side service or an appropriately authorized
    Gmail API integration.
-   Keep OAuth tokens, credentials, and secrets out of the Android
    source code.
-   Configure and verify the recipient with the user's consent.
-   Handle provider failures and show truthful delivery status.

### Google Chat

-   Send to a configured Chat space using a supported Google Chat API
    integration or incoming webhook where the account/workspace setup
    permits it.
-   A webhook posts to its configured space; it is not a general-purpose
    email sender.
-   Verify account, Workspace, permission, and API requirements before
    promising support.

### SMS or phone call

-   Consider only after checking platform rules, user consent, carrier
    availability, and permissions. Do not assume automatic SMS works on
    every device or distribution channel.

No channel guarantees instant delivery. A missing internet connection,
device power loss, background restriction, provider error, or disabled
notification can prevent or delay an alert.

## 7. Privacy and safety requirements

-   Ask for explicit permission and explain sensor/background use.
-   Monitoring must be visibly enabled and easy to stop.
-   Do not upload continuous raw sensor data by default.
-   Store only the minimum history needed; provide deletion controls.
-   Contact details must be supplied by the user, not invented.
-   Do not send real alerts during test scenarios unless the user
    explicitly chooses a real recipient and confirms the test.
-   Never claim an emergency message has been delivered without a
    success response.
-   Display a disclaimer that this is experimental and must not replace
    professional or certified safety devices.
-   Provide a manual emergency/help action where appropriate, with clear
    limitations.

## 8. Testing checklist

-   Accelerometer absent or unavailable.
-   Permission denied.
-   Monitoring not started.
-   Monitoring started and stopped correctly.
-   Ordinary walking, sitting, and lying down do not constantly trigger
    alerts.
-   A staged sensor replay triggers the candidate-event flow.
-   User cancels countdown.
-   User chooses "Send help alert now".
-   Countdown expires.
-   No contact configured.
-   No network / backend failure.
-   Test alert succeeds and fails.
-   App is backgrounded, screen locks, and device restarts.
-   Battery optimization and Android background restrictions are
    documented.
-   App never displays fake data or fake delivery success.

Use recorded/synthetic sensor traces for initial tests. Do not ask
anyone to perform a real fall to test the app.
