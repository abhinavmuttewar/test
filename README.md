# VoicePilot

Hands-free voice control for Android (built for the Galaxy S24 Ultra). Say **"hey phone"**, then a command in English or Hindi.

- **Offline commands:** back, home, recent apps, notifications, quick settings, screenshot, lock phone, scroll up/down/left/right, open \<app\>, tap \<text\>, type \<text\>, stop. Hindi works too ("वापस जाओ", "नीचे", "व्हाट्सएप खोलो", "सेंड पर क्लिक करो", "लिखो …", "रुको").
- **Anything else** ("send hi to Rahul on WhatsApp", "turn on the flashlight", "play lo-fi music on YouTube") goes to Claude. Claude reads the screen and taps, types and scrolls step by step until the task is done. If Claude asks you something, just answer; you don't need the wake word.

## Install

1. Open the repo's **Actions** tab on your phone, open the latest **Build APK** run and download **VoicePilot-apk**. Unzip it and install `app-debug.apk` (allow "Install unknown apps" for your browser or file manager).
2. Open VoicePilot and tap the buttons in order:
   1. **Allow microphone & notifications**
   2. **Turn on accessibility service** → Installed apps → VoicePilot → On.
      If Android says the setting is restricted: Settings → Apps → VoicePilot → ⋮ → **Allow restricted settings**, then try again.
   3. **Allow running in background**
3. Paste your Claude API key from console.anthropic.com. This is optional; without it only the offline commands work.
4. Tap **Save & start listening**.

## Samsung settings that keep it running

- Settings → Battery → Background usage limits → **Never auto sleeping apps** → add VoicePilot.
- Turn off the Bixby and Gemini voice wake-up if they keep grabbing the microphone.

## Limits

- After a reboot, open the app once so listening starts again (Android blocks background microphone starts).
- A PIN or password lock screen can't be unlocked by voice. Use face unlock, or Settings → Lock screen → Extend Unlock.
- Some banking or secure apps hide their screens from accessibility services.
- Each Claude request is billed to your API key. Simple tasks take a few requests.

## Build locally

You need Android Studio, or JDK 17 and the Android SDK. Download [vosk-model-small-en-us-0.15](https://alphacephei.com/vosk/models) and unzip it to `app/src/main/assets/model-en-us/`. Add a `uuid` file inside it with any text, then run `gradle assembleDebug`.
