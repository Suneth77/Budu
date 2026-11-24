"""Safety checks for everything Budu says or draws.

Design:
- What the CHILD says is NOT blocked. Small children say all kinds of things, and
  sometimes they tell us they're scared or hurt. Claude's system prompt handles
  that gently (comfort + "tell a grown-up you trust").
- What BUDU says is checked before it reaches the child.
- Image prompts get a stricter check, and they're always wrapped in a fixed,
  friendly picture-book style.

Two layers:
1. A quick word blocklist (English; image prompts are always written in English).
2. The OpenAI moderation API (multilingual, free), used when OPENAI_API_KEY is set.
   If the moderation call fails, we "fail closed" and treat the text as unsafe.
"""
import logging
import os
import re

import httpx

log = logging.getLogger("budu.safety")

BLOCKED_WORDS = [
    "kill", "killing", "killed", "murder", "gun", "guns", "rifle", "pistol",
    "knife", "knives", "sword", "weapon", "weapons", "bomb", "explode",
    "blood", "bloody", "gore", "dead", "death", "die", "dying", "suicide",
    "sex", "sexy", "naked", "nude", "kiss on the lips",
    "drug", "drugs", "beer", "wine", "vodka", "alcohol", "drunk",
    "cigarette", "smoking", "vape",
    "hate", "stupid", "idiot", "shut up",
    "password", "address", "phone number", "credit card",
]

# Extra words that are fine in a gentle conversation but not in a picture.
IMAGE_EXTRA_BLOCKED = [
    "scary", "horror", "creepy", "zombie", "demon", "devil", "skeleton",
    "skull", "monster attack", "fight", "fighting", "war", "injured", "hurt",
    "crying", "angry", "real person", "photo of",
]


def _pattern(words: list[str]) -> re.Pattern:
    return re.compile(r"\b(" + "|".join(re.escape(w) for w in words) + r")\b", re.IGNORECASE)


_BLOCKED_RE = _pattern(BLOCKED_WORDS)
_IMAGE_BLOCKED_RE = _pattern(BLOCKED_WORDS + IMAGE_EXTRA_BLOCKED)


def _moderation_flagged(text: str) -> bool:
    key = os.getenv("OPENAI_API_KEY", "")
    if not key:
        return False  # moderation not configured; blocklist + system prompt only
    try:
        r = httpx.post(
            "https://api.openai.com/v1/moderations",
            headers={"Authorization": f"Bearer {key}"},
            json={"model": "omni-moderation-latest", "input": text},
            timeout=10,
        )
        r.raise_for_status()
        return bool(r.json()["results"][0]["flagged"])
    except Exception as exc:  # network error, bad key, etc.
        log.warning("Moderation check failed (%s); treating text as unsafe", type(exc).__name__)
        return True


def is_safe_reply(text: str) -> bool:
    """Check something Budu is about to say."""
    if _BLOCKED_RE.search(text):
        return False
    return not _moderation_flagged(text)


def is_safe_image_prompt(prompt: str) -> bool:
    """Stricter check for picture requests."""
    if _IMAGE_BLOCKED_RE.search(prompt):
        return False
    return not _moderation_flagged(prompt)


def build_image_prompt(subject: str) -> str:
    """Wrap the child's idea in a fixed, gentle picture-book style."""
    subject = subject.strip().rstrip(".")
    return (
        f"A cute, friendly children's picture-book illustration of {subject}. "
        "Soft rounded shapes, bright gentle colors, happy and cozy mood, simple background. "
        "Nothing scary or sad, no weapons, no text or letters, no real people, no logos."
    )


# Used when Budu's own answer fails the safety check.
# NOTE: English only for now. Translate these if your child speaks another language.
SAFE_FALLBACK = {
    "reply": "Let's talk about something fun! What's your favorite animal?",
    "emotion": "curious",
    "image_prompt": None,
}
