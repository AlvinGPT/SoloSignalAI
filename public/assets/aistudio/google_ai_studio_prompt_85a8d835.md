# Google AI Studio Build Prompt --- SoloSignal AI (Native Android)

Build a native Android mobile app named **SoloSignal AI** using
**Kotlin, Jetpack Compose, and the Android SDK**. Do not build a website
or a web-only prototype. Use the uploaded visual reference as a
design-language reference: bold friendly typography, pale
lavender/off-white background, pastel mint/peach/lavender/sky-blue
cards, rounded corners, simple icons, generous spacing, and a polished,
approachable look. Do not copy the mood-tracking content from the
reference.

Read and follow `design.md`, `working.md`, and `sensor.md` as the
product specifications if they are available in the project context. If
those files are not accessible, follow the requirements below.

## Product

SoloSignal is an experimental personal-safety app. It monitors the
phone's motion sensors while the user explicitly enables monitoring,
screens for motion patterns that may indicate a fall, asks the user
whether they are okay, and can attempt to notify a user-configured
trusted contact if the user does not respond.

It is not a certified medical device and must never claim to guarantee
safety or emergency delivery.

## Required stack

-   Native Android project.
-   Kotlin.
-   Jetpack Compose and Material 3.
-   Android SDK sensor APIs (`SensorManager`, `SensorEventListener`).
-   Android notification APIs.
-   DataStore for simple preferences; use Room only if needed and
    available.
-   Keep code modular, readable, and buildable.
-   Do not use a browser-only implementation, HTML, React, or a web
    wrapper.

## Screens

1.  **Onboarding:** explain the app in friendly language, its
    limitations, and why permissions are needed. Monitoring is off by
    default.
2.  **Home:** show real sensor availability, truthful monitoring status,
    a Start/Stop Monitoring button, trusted-contact setup status, recent
    event status, and a clearly labelled Test Alert button.
3.  **Possible fall:** show "Are you okay?", a visible 20-second
    countdown, a large "I'm okay --- cancel alert" button, and "Send
    help alert now".
4.  **Trusted contact:** let the user enter and edit a contact name and
    email address; validate inputs and explain consent.
5.  **Activity history:** show actual locally recorded events and
    outcomes. Never seed fake events or fake readings.
6.  **Settings/privacy:** show permissions, monitoring controls, data
    handling, troubleshooting, and the experimental-use disclaimer.

Use a bottom navigation bar for Home, Activity, Contacts, and Settings
after onboarding.

## Sensor implementation

-   Check whether `Sensor.TYPE_ACCELEROMETER` exists on the current
    device.
-   Register/unregister a real `SensorEventListener` only when
    monitoring is enabled.
-   Display live x/y/z values or a simple motion indicator only if the
    values come from the actual sensor.
-   Calculate acceleration magnitude as `sqrt(x*x + y*y + z*z)`.
-   Use timestamps and a bounded rolling window.
-   Implement a clearly labelled **heuristic candidate-event detector**,
    not a claimed validated AI model. Do not trigger from one peak
    alone. Separate detector logic into a testable Kotlin class.
-   If reliable classification data/model is not available, say so in
    the UI and use the heuristic only for experimental screening.
-   Include a developer/test mode with synthetic or replayed sensor
    traces so the alert workflow can be tested without anyone performing
    a fall.
-   Do not use Gemini network calls for every sensor sample.

## Event and alert workflow

-   Monitoring starts only after the user taps Start.
-   On a candidate event, show the confirmation screen and start a
    20-second countdown.
-   "I'm okay" cancels the alert and logs the real result.
-   "Send help alert now" attempts the configured channel.
-   If the countdown expires, attempt the configured channel.
-   Prevent duplicate alerts for the same event.
-   Show accurate states: Not configured, Ready to test, Alert pending,
    Alert sent (only after confirmed success), or Alert failed.
-   If no remote alert provider is configured, complete the local
    check-in flow and clearly show that remote notification is not
    configured. Never pretend an email or Chat message was sent.
-   Provide a clearly labelled test notification that cannot be mistaken
    for an emergency.

## Trusted contact and remote messaging

For the first build, implement contact configuration and local/test
notification workflow. Do not embed API keys, OAuth secrets, Gmail
credentials, or Google Chat webhook URLs in client code.

Design a clean interface for a later secure backend integration. If an
actual backend integration is not configured, show it as unavailable
rather than fabricating delivery. Email or Google Chat must only be
added through a secure, authorized server-side integration after the
local workflow works.

## Background behavior

Implement foreground/background behavior only if compatible with the
generated Android project and the target Android SDK. For continuous
monitoring, follow the applicable Android foreground-service
requirements and show a persistent notification while monitoring.
Request runtime notification permissions where required. If robust
background operation cannot be implemented within this generated
project, state that limitation in the UI and documentation; do not
falsely claim continuous protection.

## Design details

-   Background: pale lavender/off-white.
-   Text: near-black, high contrast.
-   Pastel cards: mint, peach, lavender, sky blue, soft pink.
-   Rounded cards (20--28 dp), pill buttons, minimal shadows.
-   Large readable headings and generous spacing.
-   Friendly, reassuring copy.
-   Status must use text and icon as well as color.
-   Accessibility: screen-reader labels, scalable text, minimum 48 dp
    touch targets.
-   Do not use mood faces, mood calendar, sleep metrics, or stress
    metrics from the reference image.

## Safety and privacy

-   Monitoring is opt-in and easy to stop.
-   Do not upload continuous raw sensor data by default.
-   Ask permission before sharing contact details or optional location.
-   Do not request location unless the user enables an explicitly
    described location-sharing feature.
-   Clearly state that false positives and missed falls are possible.
-   Do not claim medical validity or guaranteed emergency response.
-   Never report an alert as delivered unless a real provider confirms
    success.

## Deliverables

1.  A complete native Android project with organized Kotlin source
    files.
2.  Working Compose navigation and screen states.
3.  Real accelerometer integration and a testable heuristic detector.
4.  A safe confirmation/countdown workflow.
5.  Local event history and truthful alert status.
6.  A README with setup/build instructions, permissions, known
    limitations, and how to test using synthetic sensor traces.
7.  A list of features that are implemented versus placeholders.

First inspect the available Android project/template and SDK
constraints. Then implement the smallest complete, buildable version. Do
not stop at a static mockup. If something cannot be completed without
external credentials, implement a clearly labelled placeholder and
explain the exact next step.
