# 🏛️ JARVIS Voice & Vision AI Assistant — Architecture Documentation

> **Official Repository:** `https://github.com/techgptx/Playlist`  
> **Original Creator & Developer:** Rehaan Sir (@TechGptX)  
> **Target Platform:** Android 8.0+ (API 26 to 34) | **Language:** Kotlin 2.0.20 | **Build System:** Gradle 8.9  

---

## 📌 1. Executive Summary & Architecture Overview

यह प्रोजेक्ट **Tech GPTx** द्वारा निर्मित **JARVIS Android AI Assistant Series (Part 1 to Part 7)** का पूर्ण प्रोडक्शन सोर्स कोड है। यह एक हाइब्रिड आर्किटेक्चर पर काम करता है जो निम्नलिखित तकनीकों को एक साथ जोड़ता है:
1. **Google Gemini Live Bidi WebSocket API**: अल्ट्रा लो-लेटेंसी ऑडियो-टू-ऑडियो स्ट्रीमिंग और नेचुरल बार्ज-इन (barge-in conversation)।
2. **Offline Vosk Speech Engine**: डिवाइस पर बिना इंटरनेट के वेक-वर्ड डिटेक्शन ("Hey Jarvis", "Jarvis wake up") और बायोमेट्रिक स्पीकर वॉइसप्रिंट वेरिफिकेशन (`model-spk`)।
3. **Android Accessibility Engine & Visual Grounding**: 21 ऑटोनॉमस टूल्स के ज़रिए पूरे फ़ोन का ऑटोमेशन (ऐप्स खोलना, टाइपिंग, क्लिक, स्क्रॉल, यूट्यूब, वॉट्सऐप, गूगल फोटोज़, सेटिंग्स आदि)।
4. **Real-time Screen Vision (MediaProjection)**: लाइव स्क्रीन कैप्चरिंग और AI विज़न रीजनिंग।
5. **3D Particle Orb & Floating Overlays**: WebGL / Three.js 3D इंटरैक्टिव ऑर्ब और 17 फ्लोटिंग सिस्टम ओवरले।
6. **Dual Persona Core**: JARVIS (Cyan / Butler AI) और NAIRA (Rose Gold / Friendly Companion)।
7. **Persistent Memory Core**: Android Room Database जो यूज़र के नाम, जन्मदिन, पसंदीदा गाने, रूटीन और प्राथमिकताओं को सहेजता है।

---

## 🗂️ 2. Repository Structure & Part Evolution

प्रोजेक्ट 7 पार्ट्स में बंटा हुआ है, जहाँ Part 7 संपूर्ण मास्टर/प्रोडक्शन वर्ज़न है:

```
Playlist-main/
├── Part 1/    # Foundation: Gemini Live Bidi WebSocket, PCM Audio Streaming & Canvas Orb
├── Part 2/    # Offline Vosk Hotword Manager & Standby Background Service
├── Part 3/    # Accessibility Engine, Automation Commands & Room Memory DB
├── Part 4/    # Low-Latency Audio Pipeline, Natural Turn-Taking & System Integration
├── Part 5/    # Advanced Accessibility Controls, YouTube/WhatsApp Automations & UI Polish
├── Part 6/    # Screen-Sharing Vision, Real-Time Multimodal Reasoning & Bugfixes
└── Part 7/    # Master Production Engine: Agentic Workflows, 17 Floating Overlays, Sound FX & Dual Persona
```

---

## 📂 3. File Map & Responsibility Matrix (काउन सी फ़ाइल क्या करती है)

Part 7 के कोर पैकेजेस (`com.jarvis.assistant`) और उनकी ज़िम्मेदारियों का विस्तृत विवरण:

### 🧠 Background & Lifecycle Management (`background/`)
* **`JarvisConversationService.kt`** (Central Core): 
  - पूरे असिस्टेंट का केंद्रीय नियंत्रण केंद्र (Foreground Service)।
  - माइक्रोफ़ोन, ऑडियो प्लेयर, Gemini Live WebSocket, 30-मिनट इनएक्टिविटी टाइमर और ऑफ़लाइन वेक-वर्ड लिसनर को मैनेज करता है।
  - यह एक्टिविटी/व्यूमॉडल से अलग बैकग्राउंड सर्विस के रूप में चलता है, जिससे ऐप बंद होने पर भी JARVIS हमेशा सुनता और बोलता रहता है।

### 🌐 Network & Gemini Live Engine (`network/`)
* **`GeminiLiveWebSocket.kt`**:
  - OkHttp WebSocket के ज़रिए Gemini Live Bidi API (`wss://generativelanguage.googleapis.com/...`) से कनेक्ट होता है।
  - 16 kHz PCM ऑडियो स्ट्रीम करता है, 24 kHz PCM ऑडियो प्राप्त करता है।
  - BidiSetup मैसेज भेजता है जिसमें सभी 21 फ़ंक्शन डिक्लेरेशन (टूल्स) और सिस्टम प्रॉम्प्ट शामिल होते हैं।
  - सर्वर साइड फ़ंक्शन कॉल्स (`tool_call`) को रिसीव करके एग्जीक्यूट करवाता है और टूल रिस्पॉन्स वापस भेजता है।

### 🎙️ Audio Pipeline & Voice Authentication (`audio/`)
* **`AudioRecorder.kt`**: माइक्रोफ़ोन से 16 kHz, 16-bit Mono PCM ऑडियो को 20 ms चंक्स में रिकॉर्ड करके WebSocket को देता है।
* **`AudioPlayer.kt`**: Gemini Live से आने वाले 24 kHz PCM ऑडियो को `AudioTrack` के माध्यम से लो-लेटेंसी में प्ले करता है। जब यूज़र बीच में बोलता है, तो बार्ज-इन (Barge-in) डिटेक्ट करके तुरंत ऑडियो रोक देता है।
* **`VoskModelManager.kt`**: ऑफ़लाइन Vosk मॉडल (`assets/model-en-us`) लोड करके बिना इंटरनेट के "Hey Jarvis" या "Jarvis" सुनता है।
* **`OwnerVoiceManager.kt`**: Vosk स्पीकर रिकग्निशन (`assets/model-spk`) के माध्यम से यूज़र के वॉइसप्रिंट को एनरोल और वेरिफ़ाई करता है, ताकि सिर्फ़ ओनर की आवाज़ पर ही JARVIS वेक हो।
* **`JarvisSoundEffects.kt`**: बिना किसी भारी MP3/WAV फ़ाइल के, प्रोग्रामैटिक वेवफ़ॉर्म सिंथेसिस द्वारा फ्यूचरिस्टिक बीप, एक्टिवेशन और एरर साउंड्स जनरेट करता है।
* **`HeadsetAudioRouter.kt`** & **`AudioUtils.kt`**: ब्लूटूथ/वायर्ड हेडसेट स्विचिंग और ऑडियो बफ़रिंग।

### 🤖 Automation & Agent Engine (`automation/`)
* **`JarvisAccessibilityService.kt`**:
  - Android Accessibility Service जो ऑन-स्क्रीन नोड्स को रीड करती है, क्लिक, टाइपिंग, स्वाइप और जेस्चर परफॉर्म करती है।
* **`agent/AgentTaskEngine.kt`** & **`agent/SerializedActionExecutor.kt`**:
  - ऑटोनॉमस मल्टी-स्टेप टास्क एक्ज़ीक्यूटर (जैसे ऐप डाउनलोड करना -> ओपन करना -> परमिशन देना -> सेटअप करना)।
* **`agent/ActionVerifier.kt`** & **`agent/ScreenSignatureCalculator.kt`**:
  - किसी एक्शन के बाद स्क्रीन बदली या नहीं, इसे स्क्रीन सिग्नेचर कैलकुलेट करके कन्फ़र्म करता है (ताकि कोई फ़र्ज़ी या अधूरा एक्शन पास न हो)।
* **`whatsapp/WhatsAppAssistant.kt`** & **`util/WhatsAppMessenger.kt`**: वॉट्सऐप मैसेजिंग, वॉयस/वीडियो कॉलिंग, और डुअल वॉट्सऐप सपोर्ट।
* **`youtube/YouTubeAutomationManager.kt`**, **`YouTubeController.kt`**, **`YouTubeApiClient.kt`**: यूट्यूब पर डायरेक्ट प्लेबैक, सर्च, लाइक, सब्सक्राइब, स्किप-ऐड और फुलस्क्रीन टॉगल।
* **`phone/PhoneControlManager.kt`** & **`contacts/ContactResolver.kt`**: फ़ोन कॉल डायलिंग और कॉन्टैक्ट मैचिंग।

### 👁️ Screen Vision & Multimodal Reasoning (`vision/`)
* **`ScreenCaptureManager.kt`**: Android MediaProjection API के ज़रिए लाइव स्क्रीन को 1-2 FPS पर कैप्चर करके JPEG फॉर्मेट में WebSocket पर स्ट्रीम करता है।
* **`UiGroundingEngine.kt`**: स्क्रीन पर दिख रहे विजुअल कोऑर्डिनेट्स (x, y) को एक्सेसिबिलिटी ट्री के साथ जोड़ता है (Visual Grounding)।
* **`VisualTaskOrchestrator.kt`**: विज़न फ़्रेम्स और टास्क प्रोग्रेस का समन्वय करता है।

### 💾 Data & Memory Core (`data/`)
* **`memory/MemoryDatabase.kt`**, **`MemoryDao.kt`**, **`MemoryEntity.kt`**, **`MemoryRepository.kt`**:
  - Room SQLite डेटाबेस जो यूज़र की व्यक्तिगत मेमोरी (नाम, पसंदीदा गाना, जन्मदिन, रूटीन, रिमाइंडर) को परमानेंट स्टोर करता है।
* **`preferences/AppPreferences.kt`**: SharedPreferences (API Key, Persona Mode, Language, Silent Mode, Selected Theme, Wake Word Sensitivity)।

---

## 🎨 4. UI Architecture & View Layer (UI कहाँ से बनता है)

### मुख्य एक्टिविटी और नेविगेशन
* **`MainActivity.kt`**:
  - सिंगल एक्टिविटी आर्किटेक्चर जो बॉटम नेविगेशन बार को मैनेज करती है।
  - 4 मुख्य टैब्स:
    1. **`HomeFragment`** (Tab 0)
    2. **`ChatFragment`** (Tab 1)
    3. **`SettingsFragment`** (Tab 2)
    4. **`UpdatesFragment`** (Tab 3)
* **`widget/CurvedDockLineView.kt`**: कस्टमाइज़्ड कर्व्ड डॉक बॉटम बार।

### होम स्क्रीन & 3D ऑर्ब (`HomeFragment.kt`)
* **`orb.html` & `three.min.js` (`assets/`)**:
  - होम स्क्रीन के सेंटर में स्थित `WebView` द्वारा रेंडर होता है।
  - Three.js द्वारा 3D पार्टिकल ऑर्ब (Cyan/JARVIS या Rose Gold/NAIRA) रेंडर करता है।
  - जब यूज़र बोलता है या JARVIS बोलता है, तो ऑडियो फ्रीक्वेंसी के अनुसार ऑर्ब में पल्स और वाइब्रेशन एनिमेशन आते हैं (`OrbHelper.kt`)।
* **Weather Card**: `LocationWeatherHelper.kt` द्वारा लाइव मौसम और टेम्परेचर।
* **Quick Actions**: स्क्रीन शेयरिंग टॉगल, मेमोरी कार्ड, माइक म्यूट/अनम्यूट।

### चैट इंटरफ़ेस (`ChatFragment.kt`)
* डुअल एडॉप्टर सिस्टम:
  - **`JarvisChatAdapter.kt`**: जार्विस पर्सोना (डार्क ब्लू / सियान बबल्स)।
  - **`NairaChatAdapter.kt`**: नायरा पर्सोना (पिंक / रोज़ गोल्ड बबल्स)।
* टेक्स्ट और वॉयस दोनों तरह से बातचीत की हिस्ट्री (`item_chat_turn.xml`, `sheet_chat_history.xml`)।

### सेटिंग्स एवं कस्टमाइज़ेशन स्क्रीन
* **`SettingsFragment.kt` / `SettingsActivity.kt`**:
  - **API Connection**: `ApiConnectionActivity.kt` (Gemini API Key, Custom Base URL, मॉडल सेलेक्शन)।
  - **Persona Mode**: `PersonaActivity.kt` (JARVIS vs NAIRA स्विचिंग)।
  - **Hands Free**: `HandsFreeActivity.kt` (वेक-वर्ड टॉगल, सेंसिटिविटी, बैकग्राउंड स्टैंडबाय)।
  - **Owner Voice Enrollment**: `VoiceEnrollmentActivity.kt` (यूज़र की आवाज़ का बायोमेट्रिक रजिस्ट्रेशन)।
  - **Overlay Avatar Toys**: `OverlayAvatarActivity.kt` (17 फ्लोटिंग ओवरले अवतार में से चुनना)।
  - **Privacy Shield**: `PrivacyShieldActivity.kt` (डेटा सुरक्षा, लोकल प्रोसेसिंग टॉगल)।

### 🎭 17 फ्लोटिंग अवतार ओवरले (`assets/overlay-toys/`)
जब JARVIS बैकग्राउंड में कोई टास्क करता है, तो स्क्रीन पर फ्लोटिंग HTML विजेट दिखाई देता है:
`alien.html`, `blob.html`, `cat.html`, `circle.html`, `cloud.html`, `droid.html`, `drop.html`, `flower.html`, `gallery.html`, `ghost.html`, `hexagon.html`, `mech.html`, `pebble.html`, `pill.html`, `puddle.html`, `square.html`, `star.html`, `triangle.html` और `hud/index.html`।

---

## 🛠️ 5. Autonomous Tool Inventory (21 Built-in Tools)

Gemini Live को दिए गए 21 टूल्स की सूची:

| No. | Tool Name | विवरण |
|---|---|---|
| 1 | `open_app` | फ़ोन में मौजूद कोई भी ऐप खोलना (डुअल ऐप सपोर्ट)। |
| 2 | `open_website` | क्रोम ब्राउज़र में विशिष्ट वेबसाइट/URL खोलना। |
| 3 | `search_in_chrome` | क्रोम में कोई टॉपिक या सवाल सर्च करना। |
| 4 | `search_and_play_youtube` | यूट्यूब पर सीधे गाना या वीडियो प्ले करना। |
| 5 | `search_youtube` | बिना प्ले किए केवल सर्च रिज़ल्ट स्क्रीन दिखाना। |
| 6 | `youtube_accessibility_action` | स्किप-ऐड, लाइक, सब्सक्राइब, चैनल ओपन, फुलस्क्रीन। |
| 7 | `media_playback_control` | प्ले, पॉज़, नेक्स्ट, प्रिवियस, स्टॉप। |
| 8 | `send_whatsapp_message` | वॉट्सऐप पर कॉन्टैक्ट को मैसेज भेजना। |
| 9 | `whatsapp_call` | वॉट्सऐप वॉयस या वीडियो कॉल लगाना। |
| 10 | `call_contact` | फ़ोन ऐप से स्टैंडर्ड फ़ोन कॉल लगाना। |
| 11 | `set_volume` | मीडिया वॉल्यूम बढ़ाना/घटाना/सेट करना। |
| 12 | `set_brightness` | स्क्रीन ब्राइटनेस एडजस्ट करना। |
| 13 | `builtin_chrome_search` | बैकग्राउंड में इंटरनेट से रीयल-टाइम फैक्ट्स/न्यूज़ खोजना। |
| 14 | `go_to_background` | ऐप मिनिमाइज़ करके स्टैंडबाय मोड में जाना। |
| 15 | `set_flashlight` | फ़ोन की टॉर्च ऑन या ऑफ़ करना। |
| 16 | `click_ui_element` | स्क्रीन के किसी भी बटन/टेक्स्ट/टॉगल पर क्लिक करना। |
| 17 | `type_text` | किसी भी इनपुट फ़ील्ड में टेक्स्ट टाइप करना। |
| 18 | `scroll_screen` | ऊपर/नीचे/दाएं/बाएं स्क्रॉल करना (स्मॉल, मीडियम, लार्ज)। |
| 19 | `press_key` | सिस्टम कीज़: 'back', 'home', 'recents'। |
| 20 | `task_completed` | पूरे टास्क के सफलतापूर्वक पूर्ण होने का सिग्नल। |
| 21 | `inspect_screen` | वर्तमान स्क्रीन का एक्सेसिबिलिटी ट्री और एलिमेंट्स रीड करना। |

---

## 🛡️ 6. Core Rules & Safety Guardrails (Zero Regression Policy)

1. **नो रिग्रेशन (Zero Regression):**
   - JARVIS और NAIRA के मौजूदा किसी भी फ़ीचर, UI, 3D Orb, साउंड इफ़ेक्ट या बैकग्राउंड सर्विस को बिगाड़ा या हटाया नहीं जाएगा।
2. **सख्त स्कोप बाउंड्री (Project Folder Only):**
   - सारा काम केवल `/root/Playlist-main` के अंदर होगा। इसके बाहर कोई फ़ाइल डिलीट या ओवरराइट नहीं होगी।
3. **सुरक्षित बैकअप:**
   - पूरा ओरिजिनल प्रोजेक्ट `/root/Playlist-backup` में सुरक्षित है और Git रिपॉज़िटरी में कमिटेड है।
