# Architektonický plán — EYEPLUS AI kamerový systém

## 1. Technologický stack

| Vrstva | Technologie | Odůvodnění |
|--------|------------|------------|
| Jazyk | Kotlin 2.0 | Moderní, null-safe, Compose-native |
| UI | Jetpack Compose + Material 3 | Deklarativní, tmavý režim, plynulé přechody |
| Architektura | MVVM + Coroutines + StateFlow | Google doporučená, lifecycle-aware |
| Video stream | Media3 ExoPlayer (RTSP) | Oficiální AndroidX, ~2MB, nativní RTSP od 1.0.0 |
| ONVIF klient | Vlastní SOAP/XML (OkHttp) | Plná kontrola, žádná neudržovaná závislost |
| Audio backchannel | RTSP + G.711 (pedroSG94 rtmp-rtsp-client) | Pošle audio do reproduktoru kamery |
| Detekce osob (on-device) | Google ML Kit Object Detection | Zdarma, offline, <100ms, první filtr |
| AI analýza scény | Gemini 2.5 Flash API (Free Tier) | 1500 requestů/den, ~0.4s latence, multimodální |
| TTS (mluvení) | Android TextToSpeech (offline) | Zdarma, okamžitá odezva |
| STT (poslech) | Android SpeechRecognizer (offline) | Zdarma |
| Nahrávání | MediaCodec + MediaMuxer (MP4) | Nativní Android API |
| Build | Gradle + Android SDK v Termuxu | Build přímo v Termuxu |
| DI | Koin (lightweight) | Jednodušší než Hilt pro střední projekt |

## 2. Struktura projektu

```
eyeplus/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/eyeplus/
│           ├── EyePlusApp.kt              # Hlavní Compose app + BottomBar navigace
│           ├── MainActivity.kt             # Single Activity
│           ├── ui/
│           │   ├── theme/                  # Theme, Colors, Typography (Material 3 dark)
│           │   ├── camera/
│           │   │   ├── CameraScreen.kt     # Živý náhled + PTZ + nahrávání
│           │   │   ├── CameraViewModel.kt
│           │   │   ├── PtzControls.kt      # Joystick/tlačítka PTZ
│           │   │   └── VideoPlayerView.kt  # Media3 ExoPlayer wrapper
│           │   ├── chat/
│           │   │   ├── ChatScreen.kt       # Textový chat s AI
│           │   │   └── ChatViewModel.kt
│           │   └── settings/
│           │       └── SettingsScreen.kt   # Nastavení IP, auth, API klíč
│           ├── data/
│           │   ├── onvif/
│           │   │   ├── OnvifClient.kt      # SOAP/XML klient (getProfiles, getStreamUri, PTZ)
│           │   │   ├── OnvifDiscovery.kt   # WS-Discovery přes UDP multicast
│           │   │   └── OnvifModels.kt      # Data třídy pro ONVIF objekty
│           │   ├── recording/
│           │   │   └── RecorderManager.kt  # MediaCodec + MediaMuxer nahrávání
│           │   ├── ai/
│           │   │   ├── GeminiAnalyzer.kt   # Volání Gemini 2.5 Flash API
│           │   │   ├── PersonDetector.kt   # ML Kit on-device detekce
│           │   │   └── AiModels.kt         # Data třídy pro analýzu
│           │   └── audio/
│           │       ├── AudioBackchannel.kt # Audio do kamery (G.711/RTP)
│           │       ├── TtsManager.kt       # Text-to-Speech
│           │       └── SttManager.kt       # Speech-to-Text
│           └── util/
│               ├── PermissionHelper.kt
│               └── NetworkUtils.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties
└── gradle/
    └── libs.versions.toml                  # Version catalog
```

## 3. Vrstvená architektura

```
+---------------------------------------------------+
|  UI Layer (Jetpack Compose)                        |
|  CameraScreen | ChatScreen | SettingsScreen       |
+---------------------------+-----------------------+
|  ViewModel Layer                                   |
|  CameraViewModel | ChatViewModel | SettingsVM     |
+---------------------------+-----------------------+
|  Domain/Data Layer                                 |
|  OnvifClient | RecorderManager | GeminiAnalyzer   |
|  Discovery    | MediaCodec       | ML Kit Detector |
|  AudioBC      | TTS/STT         | PersonDetector   |
+---------------------------------------------------+
```

**Tok dat - AI dozor:**
```
RTSP stream -> ML Kit (detekuje osobu?) -> ANO -> Capture frame -> Gemini API -> JSON analýza -> 
  -> Pokud hrozba: TTS hlášení přes reproduktor kamery + notifikace
  -> Pokud normální: log + čekej na další snímek
```

**Tok dat - Hlasový dialog:**
```
Mikrofon kamery -> STT (Android SpeechRecognizer) -> text -> Gemini API -> text odpovědi -> 
  -> TTS (Android) -> Audio backchannel -> Reproduktor kamery
```

## 4. Fáze implementace (6 logických celků)

### Fáze 2.1: Projektová kostra + Síťová vrstva
- Vytvoření Gradle projektu, Compose UI kostra + Material 3 dark theme
- Implementace `OnvifClient` (SOAP/XML přes OkHttp): GetProfiles, GetStreamUri, RelativeMove, Stop
- Implementace `OnvifDiscovery` (WS-Discovery přes UDP multicast)
- **Dependencies**: Kotlin, Compose, OkHttp

### Fáze 2.2: Video stream + PTZ ovládání
- Integrace Media3 ExoPlayer s RTSP (RtspMediaSource, force TCP)
- Implementace `VideoPlayerView` v Compose
- PTZ ovládání: RelativeMove + Stop přes ONVIF (primární)
- HTTP CGI fallback pro PTZ (metoda B)
- UI: joystick a tlačítka v Compose, CameraScreen komplet
- **Dependencies**: Media3, Fáze 2.1

### Fáze 2.3: Lokální nahrávání
- MediaCodec extraction from ExoPlayer + MediaMuxer do MP4
- Nahrávání na úložiště (Android MediaStore API)
- UI tlačítko nahrávání v CameraScreen
- Automatické nahrávání při AI detekci
- **Dependencies**: Fáze 2.2

### Fáze 2.4: AI dozor (Gemini API + ML Kit)
- Integrace Firebase AI Logic SDK (Gemini 2.5 Flash)
- ML Kit Object Detection pro on-device detekci osob (první filtr)
- Screenshot z RTSP -> downscale -> Gemini API
- Parsování JSON odpovědi -> vyhodnocení
- Prompt engineering pro detekci a popis scény
- Systém notifikací při detekci hrozby
- **Dependencies**: Fáze 2.2

### Fáze 2.5: Audio backchannel + Hlasová interakce
- Android TTS (offline) pro hlášení přes reproduktor kamery
- Android STT pro hlasové příkazy z mikrofonu kamery
- Audio backchannel: G.711 přes RTP do RTSP backchannel tracku
- Aktivní hlášení: pozdrav při detekci majitele, události
- **Dependencies**: Fáze 2.4 (AI rozhoduje o reakci)

### Fáze 2.6: Chat rozhraní + UI/UX finalizace
- ChatScreen: historie zpráv, streaming odpovědí, system prompt
- Propojení AI analýzy s chatem (AI vidí historii detekcí)
- UI/UX dolazení: Material 3 dark theme, animace, responsivita
- SettingsScreen: konfigurace IP, auth, Gemini API klíč, nahrávání
- **Dependencies**: Fáze 2.4, probíhá souběžně s 2.5

## 5. Hlavní závislosti (build.gradle.kts)

```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2025.12.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui")

// Media3 ExoPlayer pro RTSP
implementation("androidx.media3:media3-exoplayer:1.10.1")
implementation("androidx.media3:media3-exoplayer-rtsp:1.10.1")
implementation("androidx.media3:media3-ui-compose:1.10.1")

// OkHttp pro ONVIF SOAP/XML
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Firebase AI Logic SDK (Gemini API)
implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
implementation("com.google.firebase:firebase-ai")

// ML Kit on-device detekce
implementation("com.google.mlkit:object-detection:17.2.0")

// RTMP/RTSP stream klient pro audio backchannel
implementation("com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.3.0")

// Koin DI
implementation("io.insert-koin:koin-android:4.0.2")
```

## 6. Oprávnění (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

**minSdk = 26** (Android 8.0, 95%+ zařízení)
**targetSdk = 35** (Android 15)
**compileSdk = 35**

## 7. Testování a verifikace

| Fáze | Test |
|------|------|
| 2.1 | Discovery najde kameru, ONVIF vrátí profily a stream URI |
| 2.2 | RTSP stream se zobrazí s <2s latencí, PTZ pohyb funguje |
| 2.3 | Nahrávání vytvoří validní MP4 soubor |
| 2.4 | ML Kit detekuje osobu <100ms, Gemini vrátí JSON analýzu |
| 2.5 | Hlas z reproduktoru kamery, rozpoznání řeči |
| 2.6 | Chat odesílá zprávy, AI odpovídá, historie funguje |
| 3 | Komplexní test: vše integrováno, výdrž 24h provozu |
| 3a | Build signed APK: `./gradlew assembleRelease` |
