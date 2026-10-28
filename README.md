# SoloSignal AI 🚨

### AI-Powered Personal Safety System Using Smartphone Motion Sensors

**Created By:** Figo Suresh and Alvin B Abhilash 
**Project:** AI / Computer Science Project  
**Project Type:** Prototype / Academic Project

---

## 📌 About the Project

**SoloSignal AI** is an AI-assisted personal safety app concept designed to help people who may be alone and unable to call for help after a possible fall.

The system uses smartphone motion sensors such as the **accelerometer** to monitor movement. When an unusual movement pattern is detected, the app can ask the user:

> **“Are you okay?”**

If the user does not respond within the given time, the system can trigger an **SOS alert** and notify a trusted emergency contact.

The project combines **Class 11 Physics concepts, smartphone sensors, AI-based pattern analysis, Android development, and Firebase services** into one practical safety-focused application.

---

## 🎯 Objectives

The main objectives of SoloSignal AI are:

- To explore how smartphone sensors can detect sudden changes in movement.
- To understand how acceleration and motion can be used to identify possible falls.
- To use AI to analyze movement patterns rather than relying only on a single sensor reading.
- To provide an **“I’m Okay”** confirmation system after a possible fall.
- To explore automatic SOS notifications to a trusted contact.


---

## 💡 Core Idea

```text
       📱 Smartphone Sensors
              │
              ▼
      Movement Data Collected
              │
              ▼
        🤖 AI Analysis
              │
       Possible Fall?
          /         \
        NO           YES
        │             │
        ▼             ▼
   Continue      "Are You Okay?"
   Monitoring        │
                     │
              ┌──────┴──────┐
              │             │
           YES            NO RESPONSE
              │             │
              ▼             ▼
         Cancel SOS      🚨 SOS ALERT
                            │
                            ▼
                    Trusted Contact
```

---

# 🤖 AI Involvement & Disclosure

Artificial intelligence was used as part of the development and research process of **SoloSignal AI**.

AI-assisted tools were used for:

- Developing and refining the project idea.
- Exploring technical approaches for smartphone sensor processing.
- Planning the application architecture.
- Assisting with code generation and debugging.
- Designing the AI-based fall-detection workflow.
- Researching possible Android and Firebase implementation approaches.
- Improving documentation, presentation structure, and explanations.

**Google AI Studio** was one of the AI-assisted development tools used during the project.

However, AI was used as an **assistance and development tool**, not as a replacement for the student's understanding or project decisions.

The student was responsible for:

- Selecting the project concept.
- Understanding and applying the Physics concepts.
- Deciding how the system should work.
- Reviewing and adapting AI-assisted code and suggestions.
- Connecting the different components of the project.
- Testing and evaluating the prototype.
- Preparing the final project report and presentation.

### Transparency Note

This project was developed with the assistance of AI tools. AI-generated suggestions and code were reviewed, modified, and integrated as part of the student's development process.

---

# ⚙️ How SoloSignal AI Works

### 1. Movement Monitoring

The Android device uses its motion sensors to collect movement information.

The **accelerometer** provides acceleration measurements along multiple axes.

### 2. AI Analysis

The collected movement data can be analyzed as a sequence or pattern.

Instead of assuming:

```text
High acceleration = Fall
```

the system can consider a combination of movement characteristics to identify a **possible fall event**.

### 3. User Confirmation

After a possible fall is detected, the application displays an emergency confirmation screen.

```text
        POSSIBLE FALL DETECTED

             Are you okay?

              [ I'M OKAY ]

           Countdown: 10s
```

### 4. SOS Trigger

If the user confirms that they are okay, the alert process stops.

If there is no response, the system proceeds with the SOS workflow.

### 5. Emergency Notification

The system can send alert information to a trusted emergency contact.

The notification can contain information such as:

- Possible fall detected
- Time of event
- User/device information
- Available location information
- Optional emergency audio evidence

---

# 🎙️ SOS Audio Evidence Concept

An optional feature of the project is an emergency audio system.

When the user explicitly enables the feature and grants microphone permission, the application can maintain a **rolling audio buffer**.

For example:

```text
Continuous Audio Buffer

[ older audio ][ older audio ][ 15 sec recent audio ]
                                      ↑
                                Latest moment
```

If a possible fall occurs and the user does not respond, the recent audio can be preserved and uploaded securely.

The backend can then attach the recording to the emergency notification or provide a secure link to it.

### Important Privacy Principle

The feature is designed around **explicit user permission**.

It should not secretly record audio.

The system should clearly indicate when microphone access is being used and follow Android's permission and background-execution requirements.

---



# 🧠 Physics + AI

The project combines Physics and AI in different roles.

```text
       PHYSICS
          │
          ▼
 Understand movement
 and acceleration
          │
          ▼
    Sensor readings
          │
          ▼
         AI
          │
          ▼
 Analyze movement
      patterns
          │
          ▼
 Possible fall
          │
          ▼
   Safety response
```

### Physics answers:

> **What is happening to the motion?**

### AI helps answer:

> **Does this movement pattern look like a possible fall?**

This combination makes the system more useful than simply setting a fixed acceleration threshold.

---

# 🏗️ System Architecture

```text
┌──────────────────────┐
│   Android Smartphone │
│                      │
│  Accelerometer       │
│  Motion Sensors      │
│  Optional Microphone │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│   SoloSignal AI      │
│   Detection Logic    │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│   User Confirmation  │
│    "I'm Okay"        │
└──────────┬───────────┘
           │
      No Response
           │
           ▼
┌──────────────────────┐
│      Firebase        │
│                      │
│ Firestore + Storage  │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ Cloud Function /     │
│ Notification System  │
└──────────┬───────────┘
           │
           ▼
     🚨 Emergency
        Contact
```

---

# ☁️ Firebase Backend

Firebase can be used as the cloud backend for the project.

### Firebase Firestore

Used for storing structured alert information such as:

- Alert status
- Timestamp
- Device/user information
- Event metadata
- Processing status

### Firebase Storage

Used for storing optional emergency audio files.

### Cloud Functions

Cloud Functions can process an emergency event and communicate with an external email or notification service.

This keeps sensitive service credentials away from the Android application.

---

# 📱 SOS Workflow

```text
Possible Fall
      │
      ▼
Detection Event
      │
      ▼
Show "Are You Okay?"
      │
      ├──────────────► User responds
      │                    │
      │                    ▼
      │                 Stop SOS
      │
      ▼
No response
      │
      ▼
Create emergency event
      │
      ├──► Save metadata
      │
      ├──► Preserve optional audio
      │
      ▼
Upload securely
      │
      ▼
Cloud Function
      │
      ▼
Emergency notification
      │
      ▼
Trusted Contact
```

---

# 🛠️ Tools Used

### Google AI Studio

Used as an AI-assisted development and prototyping tool for:

- Project planning
- Technical exploration
- Code assistance
- AI workflow development
- Debugging and refinement

### Android Studio

Used for Android application development.

### Firebase

Used for backend services such as:

- Firestore
- Storage
- Cloud Functions

### Kotlin

Used as the primary Android programming language.

### Android Sensor APIs

Used to access smartphone motion sensor data.

---

# ✨ Main Features

- 📱 Smartphone motion monitoring
- 📈 Accelerometer-based movement analysis
- 🤖 AI-assisted fall-pattern detection
- 🚨 SOS confirmation workflow
- 👆 Large **“I'm Okay”** button
- ⏱️ Emergency countdown
- ☁️ Firebase backend integration
- 📩 Trusted-contact notification concept
- 🔄 Retry and recovery considerations for network failures

---

# 🔐 Privacy & Safety

Privacy is an important part of the project.

The system should:

- Request microphone permission before using audio.
- Never secretly record audio.
- Store emergency information securely.
- Keep cloud credentials out of the Android application.
- Clearly communicate when emergency monitoring features are active.
- Allow the user to disable optional audio functionality.
- Follow Android platform privacy and background-execution requirements.

SoloSignal AI is an **academic prototype** and should not be treated as a replacement for professional emergency, medical, or personal safety systems.

---

# ⚠️ Limitations

The prototype has several limitations.

### Sensor Limitations

Smartphone sensors can produce different readings depending on:

- Phone orientation
- Phone placement
- Device hardware
- User movement
- Environmental conditions

### Detection Limitations

Not every sudden movement is a fall.

For example:

```text
Running
Jumping
Dropping the phone
Sitting down quickly
Sudden phone rotation
```

could potentially create unusual sensor patterns.

Therefore, false positives and false negatives are possible.

### Android Limitations

Background sensor and microphone functionality can be affected by Android operating-system restrictions, permissions, battery optimization, and device-specific behavior.

### Network Limitations

Cloud-based alerts require network connectivity.

If the device has poor or no connectivity, notification delivery may be delayed or unavailable.

---


# 📊 Project Evaluation

The prototype can be evaluated using different movement scenarios.

| Scenario | Expected Result |
|---|---|
| Normal walking | No alert |
| Sitting normally | No alert |
| Phone rotation | No emergency |
| Sudden movement | Analyze pattern |
| Possible fall | Show confirmation |
| Fall + user responds | Cancel SOS |
| Fall + no response | Trigger SOS |
| Network unavailable | Retry / handle failure |

These tests help determine how reliably the system distinguishes normal movement from possible emergency events.

---

# 🎥 Project Media

## Project Presentation

`https://canva.link/solo-signal-ai`

---


---

# 📌 Project Information

| Field | Details |
|---|---|
| Project | SoloSignal AI |
| Student |Alvin B Abhilash, and Figo Suresh |

| Subject | AI / Computer Science |
| Platform | Android |
| Language | Kotlin |
| AI Tool | Google AI Studio |
| Development Tool | Android Studio |
| Backend | Firebase |
| Main Sensor | Accelerometer |
| Project Type | Academic Prototype |

---

# 🧪 Project Status

**Status:** Prototype / Academic Project

The project demonstrates how smartphone sensors, Physics concepts, AI-assisted analysis, and cloud services can be combined to explore a real-world personal safety problem.

---

# 📚 References

- Android Sensor APIs
- Android permission and background-execution documentation
- Firebase documentation
- Google AI Studio documentation

---

# ⚖️ Disclaimer

SoloSignal AI is an **educational prototype** created as part of a student project.

It is intended to demonstrate the application of Physics, mobile sensors, AI, and software development to a real-world problem.

It has not been certified as a medical device or emergency-response system.

The system may produce false positives or fail to detect some events. It should therefore **not be relied upon as the sole method of emergency protection**.

---

# 🎬 Final Note

SoloSignal AI explores a simple idea:

> **What if a phone could recognize when something might be wrong and ask for help before it is too late?**



**Built as a student project by Alvin B Abhilash, and Figo Suresh**

---
