// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

export const TRANSLATIONS = {
  en: {
    appName: "PulseMesh",
    subtitleOffGrid: "v1.5.5 Web & iPhone PWA • Double Ratchet • Acoustic 2.0",
    tabMap: "Map",
    tabChats: "Chats",
    tabVoice: "Voice",
    tabSettings: "Settings",
    nodesToggle: "Peers",
    setupTitle: "PulseMesh v1.5.5 Quick Setup & Account",
    setupSubtitle: "Official iPhone PWA, Desktop & Wi-Fi Direct Mesh Node",
    quickSetupTab: "1. Quick Setup",
    usernameTab: "2. @username (Optional)",
    iphoneGuideTab: "3. iPhone & SSL CA",
    callsignLabel: "Operator Callsign",
    channelPassphraseLabel: "Mesh Channel Passphrase (matches team)",
    privacyLabel: "GPS Privacy",
    privacyExact: "EXACT GPS",
    privacyCoarse: "COARSE (~1 km)",
    privacyOff: "OFF (Stealth)",
    themeLabel: "Theme",
    themeDark: "Dark Frutiger Aero",
    themeLight: "Daylight Aero",
    languageLabel: "Language / Γλώσσα",
    enterNodeBtn: "Enter PulseMesh Node",
    skipSetupBtn: "Continue Without Setup",
    usernameOptionalTitle: "Optional (Recommended): Reserve or Sign In @username",
    usernameOptionalNote:
      "You can use PulseMesh completely anonymously offline. Claiming an optional @username adds cross-device recovery, Cloudflare Realtime WebSockets, and iOS Web Push.",
    modeReserveBtn: "Reserve New @username (Default)",
    modeRecoverBtn: "Recover / Sign In",
    usernameInputLabel: "@username (3-24 chars)",
    pinInputLabel: "Password (6+ chars), 4-8 Digit PIN, or PMRK-... Key",
    claimSubmitBtn: "Claim New @username",
    signInSubmitBtn: "Sign In (@username)",
    sosBtn: "SOS",
    pingBtn: "PING",
    mapExportGpx: "Export GPX",
    mapRulerToggle: "Ruler",
    mapLayerDark: "Dark",
    mapLayerTopo: "Topo",
    mapLayerRadar: "Radar",
    mapDropHint: "Tap map to place waypoint or measure ruler leg",
    chatsTitle: "Encrypted Mesh Threads",
    newDmPlaceholder: "DM @username or 0xNodeId...",
    chatInputPlaceholder: "Type E2EE message...",
    sendBtn: "Send",
    peersOnlineTitle: "Live Mesh Peers",
    voiceHubTitle: "Voice Hub & Walkie-Talkie Deck",
    callPeerBtn: "WebRTC Call",
    pttHoldBtn: "HOLD TO TALK (PTT)",
    pttSpeakClipBtn: "Speak Clip (HTTP Mic fallback)",
    acousticTitle: "Bell 202 Acoustic 2.0 (RS-FEC + AES-256-GCM)",
    acousticPlaceholder: "Short air-gap message (max 72 chars)...",
    acousticTxBtn: "Chirp V2 Encrypted",
    acousticSosBtn: "Acoustic SOS",
    acousticRxOff: "Acoustic RX: OFF",
    acousticRxOn: "Acoustic RX: LISTENING",
    iosPushTitle: "iOS 16.4+ & Desktop Web Push (VAPID)",
    iosPushHint:
      "On iPhone/iPad: tap Share -> Add to Home Screen first, then enable Web Push for background notifications.",
    enablePushBtn: "Enable Background Web Push",
    disablePushBtn: "Disable Web Push",
    iosCaTitle: "Guided 1-Tap iOS .mobileconfig Root CA (Hotspot HTTPS Mic)",
    iosCaSummary: "Step-by-step trust guide for green padlock & Safari microphone on :8443",
    downloadCaBtn: "Download PulseMesh Root CA (.mobileconfig)",
    apkBeamTitle: "Offline P2P APK Beaming (Zero Internet)",
    apkBeamBtn: "Download Android APK",
    apkBeamShaMissing: "Host hotspot active; connect to Android host to beam APK.",
    purgeBotsBtn: "Purge Phantom / Stale Peers",
    settingsAccountBtn: "Account & @username",
    settingsWizardBtn: "Open Quick Setup"
  },
  el: {
    appName: "PulseMesh",
    subtitleOffGrid: "v1.5.5 Web & iPhone PWA • Double Ratchet • Acoustic 2.0",
    tabMap: "Χάρτης",
    tabChats: "Μηνύματα",
    tabVoice: "Φωνή",
    tabSettings: "Ρυθμίσεις",
    nodesToggle: "Κόμβοι",
    setupTitle: "Ρύθμιση PulseMesh v1.5.5 & Λογαριασμός",
    setupSubtitle: "Επίσημο iPhone PWA, Desktop & Κόμβος Wi-Fi Direct",
    quickSetupTab: "1. Γρήγορη Ρύθμιση",
    usernameTab: "2. @username (Προαιρετικό)",
    iphoneGuideTab: "3. iPhone & SSL CA",
    callsignLabel: "Κωδικός Κλήσης (Callsign)",
    channelPassphraseLabel: "Κωδικός Καναλιού Mesh (κοινός στην ομάδα)",
    privacyLabel: "Απόρρητο GPS",
    privacyExact: "ΑΚΡΙΒΕΣ GPS",
    privacyCoarse: "ΚΑΤΑ ΠΡΟΣΕΓΓΙΣΗ (~1 km)",
    privacyOff: "ΚΛΕΙΣΤΟ (Stealth)",
    themeLabel: "Θέμα",
    themeDark: "Σκοτεινό Frutiger Aero",
    themeLight: "Φωτεινό Aero",
    languageLabel: "Γλώσσα / Language",
    enterNodeBtn: "Είσοδος στον Κόμβο PulseMesh",
    skipSetupBtn: "Συνέχεια Χωρίς Ρύθμιση",
    usernameOptionalTitle: "Προαιρετικό (Προτείνεται): Κατοχύρωση ή Σύνδεση @username",
    usernameOptionalNote:
      "Μπορείτε να χρησιμοποιήσετε το PulseMesh ανώνυμα εκτός σύνδεσης. Ένα προαιρετικό @username δίνει ανάκτηση συσκευής, Cloudflare WebSockets και iOS Web Push.",
    modeReserveBtn: "Νέο @username (Προεπιλογή)",
    modeRecoverBtn: "Ανάκτηση / Σύνδεση",
    usernameInputLabel: "@username (3-24 χαρακτήρες)",
    pinInputLabel: "Κωδικός (6+ χαρ.), PIN 4-8 ψηφίων ή Κλειδί PMRK-...",
    claimSubmitBtn: "Κατοχύρωση @username",
    signInSubmitBtn: "Σύνδεση (@username)",
    sosBtn: "SOS",
    pingBtn: "PING",
    mapExportGpx: "Εξαγωγή GPX",
    mapRulerToggle: "Χάρακας",
    mapLayerDark: "Σκοτεινό",
    mapLayerTopo: "Τοπογραφικό",
    mapLayerRadar: "Ραντάρ",
    mapDropHint: "Πατήστε στον χάρτη για σημείο ή μέτρηση απόστασης",
    chatsTitle: "Κρυπτογραφημένες Συνομιλίες Mesh",
    newDmPlaceholder: "DM @username ή 0xNodeId...",
    chatInputPlaceholder: "Γράψτε μήνυμα E2EE...",
    sendBtn: "Αποστολή",
    peersOnlineTitle: "Ενεργοί Κόμβοι Mesh",
    voiceHubTitle: "Κέντρο Φωνής & Ασύρματος PTT",
    callPeerBtn: "Κλήση WebRTC",
    pttHoldBtn: "ΚΡΑΤΗΣΤΕ ΓΙΑ ΟΜΙΛΙΑ (PTT)",
    pttSpeakClipBtn: "Ηχητικό Κλιπ (HTTP Mic)",
    acousticTitle: "Ακουστικό Μόντεμ Bell 202 V2 (RS-FEC + AES-256-GCM)",
    acousticPlaceholder: "Σύντομο ακουστικό μήνυμα (έως 72 χαρ.)...",
    acousticTxBtn: "Εκπομπή V2 E2EE",
    acousticSosBtn: "Ακουστικό SOS",
    acousticRxOff: "Ακουστική Λήψη: OFF",
    acousticRxOn: "Ακουστική Λήψη: ΕΝΕΡΓΗ",
    iosPushTitle: "Ειδοποιήσεις Web Push iOS 16.4+ & Desktop (VAPID)",
    iosPushHint:
      "Σε iPhone/iPad: πατήστε Κοινή χρήση -> Προσθήκη στην Οθόνη Αφετηρίας πρώτα και μετά ενεργοποιήστε το Web Push.",
    enablePushBtn: "Ενεργοποίηση Web Push",
    disablePushBtn: "Απενεργοποίηση Web Push",
    iosCaTitle: "Οδηγός iOS .mobileconfig Root CA 1-Πατήματος",
    iosCaSummary: "Οδηγίες εμπιστοσύνης για πράσινο λουκέτο HTTPS :8443 και μικρόφωνο Safari",
    downloadCaBtn: "Λήψη Root CA (.mobileconfig)",
    apkBeamTitle: "Μεταφορά Android APK Εκτός Σύνδεσης (P2P)",
    apkBeamBtn: "Λήψη Android APK",
    apkBeamShaMissing: "Συνδεθείτε σε Android Hotspot για λήψη APK.",
    purgeBotsBtn: "Εκκαθάριση Παλιών Κόμβων",
    settingsAccountBtn: "Λογαριασμός & @username",
    settingsWizardBtn: "Άνοιγμα Οδηγού Ρύθμισης"
  }
};

let currentLang = "en";

export function setLanguage(lang) {
  currentLang = lang === "el" ? "el" : "en";
  if (typeof document !== "undefined") {
    document.documentElement.lang = currentLang;
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const k = el.getAttribute("data-i18n");
      if (k) el.textContent = t(k);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
      const k = el.getAttribute("data-i18n-placeholder");
      if (k) el.setAttribute("placeholder", t(k));
    });
  }
  return currentLang;
}

export function getLanguage() {
  return currentLang;
}

export function t(key) {
  const dict = TRANSLATIONS[currentLang] || TRANSLATIONS.en;
  return dict[key] || TRANSLATIONS.en[key] || key;
}
