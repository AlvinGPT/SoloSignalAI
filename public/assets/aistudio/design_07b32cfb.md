# SoloSignal AI --- Design Specification

## 1. Product direction

Build **SoloSignal AI**, an Android personal-safety app that looks and
feels like the supplied reference: friendly, bold, rounded, colorful,
minimal, and approachable. Reuse the reference's visual language---not
its mood-tracking content.

The reference's useful design cues: - Three clear mobile-screen
concepts: welcome/onboarding, main dashboard, and history/summary. -
Large friendly typography and short headlines. - Rounded pastel cards
with strong contrast. - Small expressive illustrations/icons. - Bottom
navigation with simple icons. - A white or very pale lavender
background, black text, and accents in mint green, lavender, peach, sky
blue, and soft pink. - Generous spacing, soft corners, uncluttered
layouts, and large touch targets.

## 2. Brand

-   **App name:** SoloSignal
-   **Tagline:** "A little check-in can make a big difference."
-   **Tone:** Calm, reassuring, respectful, never frightening.
-   **Primary colors:** near-black `#171717`, pale lavender `#F0ECF7`,
    mint `#B9F7A8`, lavender `#D8B4F8`, peach `#FFB38A`, sky `#A9D5FF`,
    pink `#F6B5DD`, white `#FFFFFF`.
-   **Typography:** Use Android system sans-serif or a clean rounded
    sans-serif. Headlines bold; supporting text regular.
-   **Shapes:** Cards with 20--28 dp corners, pill buttons, subtle
    borders, minimal shadows.
-   **Icons:** Material Symbols / Material Icons, consistently sized. Do
    not use emoji as the only iconography.

## 3. Main screens

### A. Welcome / onboarding

-   Headline: **"Your safety, with a little backup."**
-   Short description: "SoloSignal watches for movement patterns that
    may indicate a fall and checks that you're okay."
-   Friendly abstract illustration: a phone/shield and small pastel
    motion marks. Avoid medical or emergency-room imagery.
-   Primary CTA: **Set up SoloSignal**
-   Secondary text link: **How it works**
-   Explain clearly that this is an experimental safety aid and not a
    guaranteed emergency service.

### B. Home dashboard

-   Top greeting: "Hi there, you're all set."
-   Main status card with a large circular icon and status:
    -   "Monitoring is off" before the user explicitly starts it.
    -   "Monitoring is on" only while sensor monitoring is genuinely
        active.
-   Large primary button toggles **Start monitoring / Stop monitoring**.
-   Compact pastel cards:
    1.  **Motion sensor** --- Available / Unavailable.
    2.  **Trusted contact** --- Set up / Configured.
    3.  **Last check** --- date/time or "No checks yet".
-   Small **Test alert** button that sends only a clearly labelled test
    message; never masquerade as a real emergency.
-   Persistent visible disclaimer: "SoloSignal can miss falls or raise
    false alarms."

### C. Possible-fall confirmation

-   Use a high-contrast peach or lavender alert card, not a full-screen
    panic-red treatment.
-   Heading: **"Are you okay?"**
-   Text: "We noticed a movement pattern that may indicate a fall."
-   Large countdown, e.g. "Checking in for 20 seconds".
-   Primary large button: **I'm okay --- cancel alert**
-   Secondary button: **Send help alert now**
-   Provide vibration/sound only when permissions and device settings
    allow it.
-   If countdown ends with no response, start the configured alert
    workflow and show its actual status. Do not claim a message was sent
    unless the send operation succeeds.

### D. Trusted contact setup

-   Fields: contact name, email address and/or phone number, preferred
    alert method.
-   Explicit consent and explanation of what information will be shared.
-   Test delivery action with a "TEST --- not an emergency" label.
-   Show delivery configuration status accurately: "Not configured",
    "Ready to test", "Test sent", or "Failed".
-   Do not require Google Chat if email/SMS is simpler for the first
    release.

### E. Activity / event history

-   Timeline of detected candidate events, cancelled checks, test
    alerts, and notification attempts.
-   Each entry shows date/time and result.
-   Label uncertain events as "Possible fall event", not "Fall
    confirmed".
-   Allow the user to clear local history.
-   Avoid collecting location unless explicitly enabled and required for
    a user-selected alert feature.

### F. Settings / permissions

-   Monitoring explanation and start/stop control.
-   Sensor permission/status.
-   Notification permission/status.
-   Background monitoring status.
-   Trusted contact and alert-channel settings.
-   Privacy information and disclaimer.
-   Clear troubleshooting when battery optimization or OS restrictions
    may affect monitoring.

## 4. Navigation

Use a simple bottom navigation bar: - **Home** --- monitoring status and
actions. - **Activity** --- event history. - **Contacts** ---
trusted-contact setup. - **Settings** --- permissions, privacy, and
help.

Keep the onboarding separate from the main navigation.

## 5. Accessibility and interaction

-   Minimum touch target around 48 dp.
-   Support Android font scaling and screen readers with meaningful
    content descriptions.
-   Do not communicate status using color alone; pair colors with text
    and icons.
-   Ensure black text on pastel backgrounds has readable contrast.
-   Use clear labels and avoid technical sensor jargon on the main
    screen.
-   Provide reduced-motion-friendly transitions.
-   Avoid tiny charts and dense dashboards.

## 6. Visual do / don't

**Do** - Use pastel cards to distinguish status and information. - Keep
one primary action per screen. - Use concise copy and a friendly,
reassuring personality. - Match the reference's rounded, colorful,
polished feel.

**Don't** - Copy the mood faces, mood calendar, sleep/stress metrics, or
mood-tracking text from the reference. - Claim the app can guarantee
safety. - Display a permanent "protected" or "all clear" state when
monitoring is inactive. - Show fake sensor readings, fake alert
delivery, or fabricated event history. - Use AI-generated images as
substitutes for working Android UI components.
