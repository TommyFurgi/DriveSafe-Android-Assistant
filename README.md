# DriveSafe Android Assistant

A native Android application designed to reduce driver distraction by intelligently managing and reading aloud incoming notifications when the user is driving a vehicle. Developed as a project for the **Mobile Systems** course.

---

## 🎯 Project Objective
The goal of this project is to create a context-aware native Android application that utilizes background services and low-level system APIs to detect driving scenarios. Once driving is detected, the application automatically intercepts incoming messages from selected communication apps and reads them aloud using a local Text-To-Speech (TTS) engine, ensuring the driver's eyes remain safely on the road.

---

## 🔄 Latest Updates & Progress
> *Here you can track the current development status and latest added features.*

* **[2026-10-14]** – Project initialized, repository setup, and initial `README.md` configuration.

---

## ✨ Features

### 🟢 Must-Have Requirements
1. **Vehicle Motion Detection (`Activity Recognition API`)**  
   - Automatically detects when the user enters a vehicle using low-power system sensor fusion instead of continuous GPS tracking.
2. **Background Operation (`Foreground Services`)**  
   - Ensures the app runs reliably in the background with a persistent notification while monitoring motion and system events.
3. **Notification Interception (`NotificationListenerService`)**  
   - Listens to incoming system notifications from messaging apps and extracts text content securely.
4. **Text-To-Speech Conversion (`TTS Engine`)**  
   - Converts intercepted text messages into natural speech so the user can hear them without looking at the screen.

### 🟡 Nice-to-Have / Optional Features
1. **Extended Activity Recognition**  
   - Additional detection support for cycling scenarios.
2. **App Whitelist / Custom Selection**  
   - A user-friendly settings screen allowing the user to choose which specific applications (e.g., SMS, WhatsApp, Messenger) are allowed to trigger the reading assistant.

---

## 🛠️ Technology Stack
- **Language:** Java
- **Platform:** Android SDK
- **Key APIs:** 
  - `ActivityRecognition API` (Google Play Services)
  - `NotificationListenerService`
  - `TextToSpeech (TTS)`
  - `Foreground Service`

---

## 👥 Project Team
* [Tomasz Furgała](https://github.com/TommyFurgi)
