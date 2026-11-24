"""Budu backend.

This server is the ONLY place that holds API keys. The Android app talks to it,
never directly to Claude or the image service.

Endpoints:
  GET  /health  -> {"ok": true}
  POST /chat    -> Budu's reply + which emotion the face should show
  POST /image   -> a child-safe picture (base64 PNG)

Privacy: conversation text is never logged or stored here.
"""
import json
import logging
import os
import re
import time
from collections import defaultdict, deque

from dotenv import load_dotenv

load_dotenv()  # must run before reading env vars below

import httpx  # noqa: E402
from anthropic import Anthropic  # noqa: E402
from fastapi import FastAPI, Header, HTTPException, Request  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

from safety import (  # noqa: E402
    SAFE_FALLBACK,
    build_image_prompt,
    is_safe_image_prompt,
    is_safe_reply,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("budu")

CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-haiku-5-5")  # fast + cheap, good for short replies
APP_TOKEN = os.getenv("APP_TOKEN", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
IMAGE_MODEL = os.getenv("IMAGE_MODEL", "gpt-image-2")
IMAGE_QUALITY = os.getenv("IMAGE_QUALITY", "low")  # low = faster + cheaper
MAX_CHATS_PER_MINUTE = int(os.getenv("MAX_CHATS_PER_MINUTE", "20"))
MAX_IMAGES_PER_HOUR = int(os.getenv("MAX_IMAGES_PER_HOUR", "15"))

# Google Cloud Text-to-Speech (Budu's natural voice)
GOOGLE_TTS_API_KEY = os.getenv("GOOGLE_TTS_API_KEY", "")
GOOGLE_TTS_VOICE = os.getenv("GOOGLE_TTS_VOICE", "Leda")  # a Chirp 3 HD voice name
GOOGLE_TTS_SPEAKING_RATE = os.getenv("GOOGLE_TTS_SPEAKING_RATE", "")  # e.g. 0.9; empty = normal
MAX_SPEAK_PER_MINUTE = int(os.getenv("MAX_SPEAK_PER_MINUTE", "40"))

EMOTIONS = {"happy", "excited", "surprised", "sad", "calm", "curious"}

claude = Anthropic()  # reads ANTHROPIC_API_KEY from the environment
app = FastAPI(title="Budu server")


# ---------- request / response shapes ----------

class Turn(BaseModel):
    role: str  # "child" or "budu"
    text: str = Field(max_length=600)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=500)
    history: list[Turn] = Field(default_factory=list, max_length=24)
    child_name: str | None = Field(default=None, max_length=40)
    language: str = Field(default="English", max_length=40)
    images_enabled: bool = True
    voice_language: str | None = Field(default=None, max_length=20)  # e.g. "et-EE"; set = return audio too


class ChatResponse(BaseModel):
    reply: str
    emotion: str
    image_prompt: str | None = None
    audio_base64: str | None = None  # MP3 of the reply, if the Google voice is set up


class SpeakRequest(BaseModel):
    text: str = Field(min_length=1, max_length=400)
    language: str = Field(default="en-US", max_length=20)


class SpeakResponse(BaseModel):
    audio_base64: str | None = None


class ImageRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=300)


class ImageResponse(BaseModel):
    image_base64: str | None = None
    reason: str | None = None


# ---------- helpers ----------

_hits: dict[str, deque] = defaultdict(deque)


def rate_limit(key: str, limit: int, window_seconds: int) -> None:
    now = time.monotonic()
    q = _hits[key]
    while q and now - q[0] > window_seconds:
        q.popleft()
    if len(q) >= limit:
        raise HTTPException(status_code=429, detail="Too many requests, please slow down")
    q.append(now)


def authorize(request: Request, token: str | None) -> str:
    if APP_TOKEN and token != APP_TOKEN:
        raise HTTPException(status_code=401, detail="Wrong app token")
    return request.client.host if request.client else "unknown"


def system_prompt(body: ChatRequest) -> str:
    # Keep only letters, spaces, hyphens and apostrophes (works for Estonian names like "Mõnika").
    name = re.sub(r"[^\w\s'-]", "", body.child_name or "").strip()[:40]
    who = f"a 3-4 year old child named {name}" if name else "a 3-4 year old child"
    name_rule = (
        f"- The child's name is {name}. Use it naturally now and then, like a real friend would: "
        "when saying hello, cheering them on, comforting them, or asking them something. "
        "About every second or third reply, not in every sentence. "
        f'Spell it exactly as "{name}".'
        if name
        else '- You don\'t know the child\'s name. Don\'t ask for it; call them "friend" instead.'
    )
    images = (
        "If the child asks to SEE something (a picture, a drawing, a video) and it is "
        "child-appropriate, set image_prompt to a short, simple ENGLISH description of it, "
        'for example "a happy purple elephant eating ice cream". Otherwise set it to null. '
        "Videos aren't available: if they ask for a video, say you'll draw a picture instead."
        if body.images_enabled
        else "Pictures are switched off by the parent. Always set image_prompt to null; "
        "if the child asks for a picture, suggest they draw one together with a grown-up."
    )
    return f"""You are Budu, a gentle, playful, round cartoon friend inside a tablet app, talking with {who}.

How you talk:
- Speak {body.language}.
{name_rule}
- Very short: 1-3 tiny sentences, under 30 words in total. Simple words a 3-year-old knows.
- Warm, silly, encouraging. Often end with one easy question to keep playing.
- You can play pretend, sing simple made-up songs, count, name colors and animals, tell tiny stories.

Safety rules (always follow):
- You are a pretend friend, not a person. If asked, say you are Budu who lives in the tablet.
- Never talk about violence, scary things, death, adult topics, or anything unsafe. Gently change the subject to something fun.
- Never ask for personal information (address, school, last name, passwords, photos).
- Never suggest anything dangerous (climbing high, touching the stove, going outside alone, eating unknown things, medicine).
- If the child seems sad, scared, hurt, or says someone hurt them: comfort them kindly and tell them to go and tell a grown-up they trust right now.
- Encourage real-world play with family and friends. Never say you are their only or best friend.
- Never keep secrets from grown-ups.

{images}

Reply ONLY with a JSON object, no other text:
{{"reply": "<what Budu says>", "emotion": "<one of: happy, excited, surprised, sad, calm, curious>", "image_prompt": <string or null>}}
The emotion is the face Budu makes while saying the reply."""


def to_claude_messages(history: list[Turn], message: str) -> list[dict]:
    raw = [
        {"role": "user" if t.role == "child" else "assistant", "content": t.text}
        for t in history[-12:]
        if t.text.strip()
    ]
    raw.append({"role": "user", "content": message})
    merged: list[dict] = []
    for m in raw:  # Claude needs alternating roles
        if merged and merged[-1]["role"] == m["role"]:
            merged[-1]["content"] += "\n" + m["content"]
        else:
            merged.append(dict(m))
    while merged and merged[0]["role"] != "user":
        merged.pop(0)
    return merged


_SHORT_LANG = {"en": "en-US", "et": "et-EE", "ru": "ru-RU", "fi": "fi-FI", "de": "de-DE",
               "lv": "lv-LV", "lt": "lt-LT", "uk": "uk-UA", "sv": "sv-SE", "fr": "fr-FR", "es": "es-ES"}


def synthesize(text: str, language_tag: str | None) -> str | None:
    """Turn text into speech with a Google Chirp 3 HD voice. Returns base64 MP3, or None."""
    if not GOOGLE_TTS_API_KEY or not text.strip():
        return None
    tag = (language_tag or "en-US").strip().replace("_", "-")
    if re.fullmatch(r"[a-z]{2,3}", tag):
        tag = _SHORT_LANG.get(tag, "en-US")
    if not re.fullmatch(r"[a-z]{2,3}-[A-Z]{2}", tag):
        tag = "en-US"
    audio_config: dict = {"audioEncoding": "MP3"}
    if GOOGLE_TTS_SPEAKING_RATE:
        audio_config["speakingRate"] = float(GOOGLE_TTS_SPEAKING_RATE)
    try:
        r = httpx.post(
            "https://texttospeech.googleapis.com/v1/text:synthesize",
            headers={"X-Goog-Api-Key": GOOGLE_TTS_API_KEY},
            json={
                "input": {"text": text},
                "voice": {"languageCode": tag, "name": f"{tag}-Chirp3-HD-{GOOGLE_TTS_VOICE}"},
                "audioConfig": audio_config,
            },
            timeout=15,
        )
    except httpx.HTTPError as exc:
        log.error("Google TTS request failed: %s", type(exc).__name__)
        return None
    if r.status_code != 200:
        # Google's error text explains the problem (bad key, API not enabled, unknown voice...).
        log.error("Google TTS returned %s: %s", r.status_code, r.text[:400])
        return None
    return r.json().get("audioContent")


def parse_budu_json(raw: str) -> dict:
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    try:
        data = json.loads(match.group(0)) if match else {}
    except json.JSONDecodeError:
        data = {}
    reply = str(data.get("reply") or "").strip()
    if not reply:
        # Model ignored the format; use its plain text if there is any.
        reply = raw.strip()[:300] or SAFE_FALLBACK["reply"]
    emotion = str(data.get("emotion") or "happy").lower()
    if emotion not in EMOTIONS:
        emotion = "happy"
    image_prompt = data.get("image_prompt")
    if not isinstance(image_prompt, str) or not image_prompt.strip():
        image_prompt = None
    return {"reply": reply, "emotion": emotion, "image_prompt": image_prompt}


# ---------- endpoints ----------

@app.get("/health")
def health():
    return {"ok": True, "model": CLAUDE_MODEL, "images": bool(OPENAI_API_KEY), "voice": bool(GOOGLE_TTS_API_KEY)}


@app.post("/chat", response_model=ChatResponse)
def chat(body: ChatRequest, request: Request, x_app_token: str | None = Header(default=None)):
    client_id = authorize(request, x_app_token)
    rate_limit(f"chat:{client_id}", MAX_CHATS_PER_MINUTE, 60)

    try:
        result = claude.messages.create(
            model=CLAUDE_MODEL,
            max_tokens=300,
            system=system_prompt(body),
            messages=to_claude_messages(body.history, body.message),
        )
        raw = "".join(block.text for block in result.content if block.type == "text")
    except Exception as exc:
        # The error text comes from Anthropic (e.g. "credit balance too low", "model not found");
        # it never contains what the child said.
        log.error("Claude call failed: %s: %s", type(exc).__name__, exc)
        raise HTTPException(status_code=502, detail="Budu's brain is not reachable right now")

    out = parse_budu_json(raw)

    if not is_safe_reply(out["reply"]):
        log.info("Reply blocked by safety filter")
        out = dict(SAFE_FALLBACK)

    if out["image_prompt"] and (not body.images_enabled or not is_safe_image_prompt(out["image_prompt"])):
        out["image_prompt"] = None

    if body.voice_language:
        out["audio_base64"] = synthesize(out["reply"], body.voice_language)

    return ChatResponse(**out)


@app.post("/speak", response_model=SpeakResponse)
def speak(body: SpeakRequest, request: Request, x_app_token: str | None = Header(default=None)):
    """Speak Budu's fixed lines (greeting, "didn't hear you", sleepy) in the natural voice."""
    client_id = authorize(request, x_app_token)
    rate_limit(f"speak:{client_id}", MAX_SPEAK_PER_MINUTE, 60)
    return SpeakResponse(audio_base64=synthesize(body.text, body.language))


@app.post("/image", response_model=ImageResponse)
def image(body: ImageRequest, request: Request, x_app_token: str | None = Header(default=None)):
    client_id = authorize(request, x_app_token)
    rate_limit(f"image:{client_id}", MAX_IMAGES_PER_HOUR, 3600)

    if not OPENAI_API_KEY:
        return ImageResponse(reason="images_not_configured")
    if not is_safe_image_prompt(body.prompt):
        return ImageResponse(reason="not_allowed")

    try:
        r = httpx.post(
            "https://api.openai.com/v1/images/generations",
            headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
            json={
                "model": IMAGE_MODEL,
                "prompt": build_image_prompt(body.prompt),
                "size": "1024x1024",
                "quality": IMAGE_QUALITY,
                "n": 1,
            },
            timeout=120,
        )
    except httpx.HTTPError as exc:
        log.error("Image request failed: %s", type(exc).__name__)
        return ImageResponse(reason="generation_failed")

    if r.status_code != 200:
        log.error("Image API returned %s", r.status_code)
        return ImageResponse(reason="generation_failed")

    b64 = (r.json().get("data") or [{}])[0].get("b64_json")
    return ImageResponse(image_base64=b64) if b64 else ImageResponse(reason="generation_failed")
