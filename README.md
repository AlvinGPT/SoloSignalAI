# SoloSignal AI — Experimental Personal-Safety Android App

> **Important Safety Disclaimer:** SoloSignal AI is an experimental engineering prototype that screens phone motion sensor patterns for possible falls and runs a cancelable check-in countdown. **It is NOT a certified medical device and never guarantees fall detection, safety, or emergency alert delivery.** False positives (e.g., dropping the phone) and missed falls are possible.

---

## 1. Overview & Architecture

**SoloSignal AI** is built natively for Android using **Kotlin**, **Jetpack Compose (Material 3)**, **Android SDK Sensor APIs** (`SensorManager`, `SensorEventListener`), **Room**, and **Jetpack DataStore**.

### Source Organization
- `com.example.domain.Models.kt` — Domain models (`MotionSample`, `MotionFeatures`, `CandidateEventSummary`, `TrustedContact`), confirmation state machine (`MonitoringState`), and delivery status enums (`DeliveryConfigStatus`).
- `com.example.sensor.CandidateFallDetector.kt` — Deterministic, testable heuristic candidate-event detector using a bounded rolling ring buffer (`ArrayDeque`), monotonic nanosecond timestamps, vector magnitude `sqrt(x*x + y*y + z*z)`, sample-to-sample `deltaMagnitude`, 3D orientation/tilt shift estimation, post-impact stillness verification, and a 15-second cooldown/re-arm period.
- `com.example.sensor.SyntheticSensorTraces.kt` — 8 prerecorded/synthetic motion trace scenarios (`Staged Possible Fall`, `Phone Dropped on Floor`, `Normal Walking`, `Sitting Down Quickly`, `Lying Down Intentionally`, `Jumping / Active Exercise`, `Single Isolated Peak`, `Orientation Change Without Fall`) so the detector and check-in workflow can be tested safely without anyone performing a fall.
- `com.example.sensor.SensorMonitorManager.kt` & `MonitoringForegroundService.kt` — Runtime hardware availability checks (`Sensor.TYPE_ACCELEROMETER`, optional `TYPE_LINEAR_ACCELERATION` / `TYPE_ROTATION_VECTOR`), strict opt-in listener registration/unregistration, and foreground service persistent notification.
- `com.example.data.EventHistoryDatabase.kt` & `PreferencesRepository.kt` — Room database for actual recorded events (never seeded with fake data) and DataStore for trusted contact configuration and countdown settings.
- `com.example.alerts.AlertDeliveryGateway.kt` — Clean `RemoteAlertGateway` interface for a future server-side messaging backend, paired with `LocalNotificationHelper` for local check-in prompts and clearly labelled `"TEST — not an emergency"` notifications.
- `com.example.ui.*` — Jetpack Compose screens (`OnboardingScreen`, `HomeScreen`, `PossibleFallCheckInCard`, `TrustedContactScreen`, `ActivityHistoryScreen`, `SettingsPrivacyScreen`) styled after the pastel reference aesthetic.

---

## 2. Setup & Build Instructions

1. Open the project in Android Studio or build via Gradle CLI (`compileSdk = 36`, `minSdk = 24`).
2. Assemble the debug APK:
   ```bash
   gradle assembleDebug
   ```
3. Run the unit and Robolectric test suites:
   ```bash
   gradle :app:testDebugUnitTest
   ```
4. No external API keys, OAuth secrets, Gmail credentials, or Google Chat webhook URLs are embedded in client code or required to build and test the local prototype.

---

## 3. Permissions & Privacy

| Permission | Type | Why It Is Requested |
| :--- | :--- | :--- |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) | Displays high-priority `"Are you okay?"` check-in notifications, clearly labelled `"TEST — not an emergency"` alerts, and the persistent foreground monitoring indicator. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Install-time | Allows `MonitoringForegroundService` to show a truthful ongoing notification while the user explicitly enables monitoring. |
| `VIBRATE` | Install-time | Provides a gentle vibration pulse when a possible-fall check-in countdown begins (can be toggled off in Settings). |
| Location (`ACCESS_FINE_LOCATION`) | **Not requested** | Location collection is disabled by default for privacy and is not requested unless an explicit location-sharing feature is added later. |

---

## 4. How to Test Safely Using Synthetic Sensor Traces

**Never ask anyone to perform a real fall to test SoloSignal.**

1. Complete the **Onboarding** screen by tapping **Set up SoloSignal**.
2. Open the **Contacts** tab, enter a contact name, a valid email address, check the explicit consent box, and tap **Save trusted contact**.
3. Tap **Test alert (TEST — not an emergency)** on the **Home** or **Contacts** screen to verify the local test notification and inspect the generated payload preview.
4. On the **Home** screen, scroll to the **Developer / Safe Trace Replay** card:
   - Tap **Normal Walking**, **Sitting Down Quickly**, **Lying Down Intentionally**, **Jumping / Active Exercise**, **Single Isolated Peak**, or **Orientation Change Without Fall** -> Observe live `x/y/z` and `|a| = sqrt(x²+y²+z²)` telemetry update and verify that **none** of these non-fall traces trigger a false alarm.
   - Tap **Staged Possible Fall Sequence** or **Phone Dropped on Floor (Synthetic)** -> Verify that the multi-feature sequence triggers the **"Are you okay?"** confirmation card with the live **20-second countdown**.
5. Test both check-in branches:
   - Tap **"I'm okay — cancel alert"** -> Cancels the countdown immediately and logs `"Cancelled by user (I'm okay)"` in the **Activity** tab.
   - Tap **"Send help alert now"** (or let the 20-second countdown expire) -> Attempts the configured alert workflow, posts the local notification, and truthfully reports `"Alert failed (Remote backend not configured)"` (or `"Test sent (Local only)"` if Local-Only mode is selected) without fabricating remote email/Chat delivery.

---

## 5. Known Limitations

- **Heuristic Screening Only:** `CandidateFallDetector` uses deterministic physics features (acceleration dip, multi-sample impact peak, 3D gravity vector tilt shift, and post-impact stillness). It is not a clinically validated ML classifier. Phone drops onto hard surfaces can mimic fall patterns and trigger the check-in screen.
- **Android OS & Battery Restrictions:** Even with a foreground service, aggressive OEM battery savers, Doze mode, or screen-off sensor batching can delay or suspend accelerometer events on some physical devices.
- **No Remote Server Configured in Client Build:** To keep OAuth secrets, SMTP credentials, and Google Chat webhook URLs out of the APK, remote Email and Google Chat delivery are intentionally unconfigured in this client-only build.

---

## 6. Implemented Features vs. Placeholders

### Implemented
- Complete 6-screen Jetpack Compose UI following the pastel visual specification (`Onboarding`, `Home`, `Possible-Fall Check-In`, `Trusted Contact`, `Activity History`, `Settings & Privacy`).
- Real `SensorManager` runtime inspection for `Sensor.TYPE_ACCELEROMETER` (plus optional linear acceleration and rotation vector checks) and strict opt-in `SensorEventListener` registration/unregistration.
- Standalone, unit-tested `CandidateFallDetector` with bounded rolling window, `sqrt(x²+y²+z²)` magnitude, delta magnitude, tilt shift, post-event stillness, single-spike rejection, and 15-second duplicate-alert cooldown.
- 8-scenario `SyntheticSensorTraces` replay harness for safe testing.
- Cancelable 20-second check-in countdown with duplicate-alert prevention per event ID.
- Local event history stored in Room (`EventHistoryDatabase`) with zero fake/seeded data and full deletion control.
- Persistent contact & preferences storage via Jetpack DataStore (`PreferencesRepository`).
- Local Android notification channels and foreground service (`MonitoringForegroundService`).

### Placeholders (Next Steps for External Integration)
- **Remote Email / Google Chat Delivery (`RemoteAlertGateway`):** The client implements `RemoteAlertGateway` and formats the complete `AlertMessagePayload`, but `LocalAndPlaceholderAlertGateway` truthfully reports remote Email/Chat as unavailable. **Next step:** Deploy a Firebase Cloud Function or authenticated HTTPS endpoint that holds the SMTP/Gmail OAuth or Google Chat space webhook credentials on the server, and implement `RemoteAlertGateway.attemptAlertDelivery()` to POST the payload over HTTPS and return `AlertDeliveryResult.ConfirmedRemoteSuccess` only on HTTP 200 confirmation.
- **Trained Time-Series ML Classifier:** **Next step:** Collect and label multi-device, multi-placement fall and daily-activity datasets, train/validate a LiteRT (TensorFlow Lite) time-series model, and plug it behind the detector interface alongside calibrated sensitivity/false-alarm metrics.
