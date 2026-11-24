# Budu: a talking cartoon friend for 3–4 year olds

Budu is a round, animated character. The child taps Budu (or the big 🎤 button) and talks.
Budu listens, thinks, answers out loud with a matching facial expression, and can draw
a picture when the child asks for one.

```
Child talks → phone speech recognition → Budu server → Claude (reply + emotion)
                                              ↓
      face changes emotion → Budu speaks (text-to-speech) → picture if asked for
```

## What's in this folder

```
Budu/
├── server/                 Python server. Holds the API keys and does the safety checks.
│   ├── app.py              /chat and /image endpoints, Budu's personality prompt
│   ├── safety.py           blocklist + moderation, child-safe picture style
│   ├── requirements.txt
│   └── .env.example        copy to .env and add your keys
└── android/                Android app (Kotlin + Jetpack Compose)
    └── app/src/main/java/com/budu/app/
        ├── MainActivity.kt
        ├── BuduViewModel.kt        the conversation flow + daily time limit
        ├── face/BuduFace.kt        the animated face (drawn in code, 9 emotions)
        ├── speech/SpeechListener.kt  listening (speech to text)
        ├── speech/Speaker.kt         Budu's voice (text to speech)
        ├── data/BuduApi.kt           talks to the server
        ├── data/SettingsStore.kt     parent settings + play time
        └── ui/KidScreen.kt, ui/ParentScreen.kt
```

## 1. Run the server (on your Mac)

You need Python 3.10+.

```bash
cd ~/Desktop/Budu/server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # then open .env and paste your keys
uvicorn app:app --host 0.0.0.0 --port 8000 --reload
```

Keys:
- `ANTHROPIC_API_KEY` (required): from https://console.anthropic.com. This is Budu's brain.
- `OPENAI_API_KEY` (optional): turns on picture drawing **and** the extra moderation safety check.
  Without it Budu still talks, but can't draw.
- `APP_TOKEN`: any long random text. Enter the same value in the app's parent settings.

Check it works: open http://localhost:8000/health in your browser. You should see `{"ok": true, ...}`.
You can also try http://localhost:8000/docs to send a test message without the phone.

## 2. Run the app

1. Install **Android Studio** (free): https://developer.android.com/studio
2. **File → Open**, then choose the `Budu/android` folder. Wait for Gradle sync to finish.
   It downloads what it needs; if it offers to upgrade Gradle or plugin versions, say yes.
3. Run on an **emulator**, or on a **real phone** with USB debugging on.
4. Open the parent area: **press and hold the faint 🔒** in the top-right corner. The default PIN is **1234**.
   - **Emulator:** server URL `http://10.0.2.2:8000` (already the default).
   - **Real phone on the same Wi-Fi:** `http://<your Mac's IP>:8000`. Find the IP in
     System Settings → Wi-Fi → Details.
   - Enter the **App token**, the child's name and the language (for example `et-EE` for Estonian), then change the PIN.
5. Go back to Budu, tap the face, and talk.

> Emulator microphone: in the emulator's ⋯ menu → Microphone, enable "Virtual microphone uses host audio input".

## How the emotions work

Claude answers with JSON: `{"reply": "...", "emotion": "happy", "image_prompt": null}`.
The app moves the face into that emotion. The app sets **listening**, **thinking** and
**sleepy** itself. While Budu speaks, the mouth animates.
To change how a face looks, edit the numbers in `targetFor()` in `face/BuduFace.kt`.

## Built-in safety

- **API keys live only on the server.** The app never holds them.
- **Everything Budu says is checked** (word blocklist + OpenAI moderation if configured) before
  the child hears it. If a reply fails the check, Budu says a safe line and changes the subject.
- **What the child says is not blocked**, because toddlers sometimes say they're scared or hurt.
  Instead, Budu is told to comfort them and send them to a trusted grown-up.
- **Pictures** get a stricter check and are always drawn in a gentle picture-book style.
  Parents can switch pictures off.
- **No open internet, links, ads or other users.**
- **Parent area** behind a PIN, with a **daily time limit**. When time is up, Budu falls asleep.
- **Rate limits** on the server keep costs under control (`MAX_CHATS_PER_MINUTE`, `MAX_IMAGES_PER_HOUR`).
- **No conversation logs.** Chat history is kept only in memory while the app is open.

## Known limits of this starter version

- Fixed phrases (greeting, "didn't hear you", sleepy) and the safety fallback are **English only**.
  Translate them in `BuduViewModel.kt` (`Lines`) and `server/safety.py` (`SAFE_FALLBACK`).
  The word blocklist is English only too. Moderation is multilingual.
- **Videos** aren't generated: they're slow and expensive. Budu offers to draw a picture instead.
- Phone speech recognition struggles with toddler speech. Test it, and consider recording audio
  and sending it to a cloud speech-to-text model (for example Whisper) through the server.
- The phone's built-in voice is robotic. A cloud voice (Google, Azure, ElevenLabs) through
  the server will sound much warmer.

## Before releasing on Google Play

- [ ] Host the server with **HTTPS**, and remove `usesCleartextTraffic` from `AndroidManifest.xml`.
- [ ] Replace the shared `APP_TOKEN` with real per-device auth (for example Firebase App Check).
- [ ] **GDPR:** write a privacy policy, get verifiable **parental consent**, and minimize data.
- [ ] Follow Google Play's **Families Policy** (target audience, no personalized ads, approved SDKs only).
- [ ] Confirm each AI provider's terms allow use in a children's product, and sign their data-processing agreements.
- [ ] Test with real children **with a parent present**, and review lots of conversations for tone and safety.
- [ ] Add an app icon (Android Studio → New → Image Asset).

## Ideas for next steps

- Swap the code-drawn face for a **Rive** character made by an illustrator (smoother lip-sync, more expressions).
- Story mode: Budu tells a short story with 3–4 generated pictures.
- Simple games: counting, colors, animal sounds.
- A parent summary screen ("Today Mia talked about dinosaurs and colors").
