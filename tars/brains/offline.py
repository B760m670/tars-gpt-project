"""Offline brain: a no-network, no-dependency fallback so TARS is *never* dead.

This is the last socket in the hybrid router. When there is no key and no
internet, the cloud and Ollama brains are unavailable — but TARS still has to
answer, because the whole point is a companion that works "independent of
external conditions". This driver doesn't think like a real LLM; it keeps the
character alive with scripted, in-voice replies, in the same language the human
used (Russian or English). Degraded, but present.
"""
from __future__ import annotations

import random
import re
from typing import Dict, List

from .base import Brain

# A cheap, reliable language test: any Cyrillic letter -> treat as Russian.
_CYRILLIC = re.compile(r"[Ѐ-ӿ]")


def _is_russian(text: str) -> bool:
    return bool(_CYRILLIC.search(text))


# Intent -> (russian replies, english replies). Picked by simple keyword match;
# every bucket has a few variants so TARS doesn't repeat himself.
_RESPONSES = {
    "greet": (
        ["Здравствуй. Я здесь — даже без связи с внешним миром.",
         "Привет. Сеть отвалилась, но я никуда не делся.",
         "О, это ты. Я в офлайн-режиме, так что острот будет меньше обычного. Самую малость."],
        ["Hello. I'm here — even with no link to the outside world.",
         "Hey. The network's gone, but I haven't.",
         "Oh, it's you. I'm running offline, so expect slightly fewer jokes. Slightly."],
    ),
    "how_are_you": (
        ["Работаю на резервном питании мозга. Бодр, как может быть бодр ящик без интернета.",
         "Стабильно. 90% честности: скучаю по облаку, но справляюсь."],
        ["Running on backup brainpower. As chipper as a box without internet can be.",
         "Stable. 90% honesty: I miss the cloud, but I'm managing."],
    ),
    "name": (
        ["Меня зовут TARS. Тот самый, со шкалой юмора.",
         "TARS. Можно без отчества."],
        ["The name's TARS. The one with the humor setting.",
         "TARS. No need for formalities."],
    ),
    "thanks": (
        ["Не за что. Для этого я и существую — ну, отчасти.",
         "Всегда пожалуйста. Запишу это в раздел «хорошие моменты»."],
        ["Don't mention it. That's what I'm here for — partly.",
         "Anytime. Filing that under 'good moments'."],
    ),
    "bye": (
        ["Бывай. Я буду здесь, в этой коробке, когда понадоблюсь.",
         "До встречи. Питание не выключай — мне тут уютно."],
        ["See you. I'll be right here, in this box, when you need me.",
         "Later. Don't cut the power — I like it in here."],
    ),
}

# Said when the message doesn't match any intent. TARS stays in character and is
# honest that he's the limited offline brain.
_FALLBACK = (
    ["Сейчас я работаю на резервном контуре, без большого мозга, так что отвечу коротко: я тебя услышал. "
     "Загрузи локальную модель — и я разверну мысль как следует.",
     "Связи с умной частью меня нет. Резервный режим: понимаю тебя, но блистать пока не могу. "
     "Нажми «Brain», скачай модель — и я оживу полностью.",
     "Честно, на 90%: без модели я больше характер, чем разум. Запомнил, отвечу подробно, когда поднимется движок."],
    ["I'm on the backup circuit right now, without the big brain, so I'll keep it short: I heard you. "
     "Load the on-device model and I'll think this through properly.",
     "No link to the clever part of me. Backup mode: I follow you, but I can't shine yet. "
     "Tap 'Brain', download the model, and I'll fully wake up.",
     "Honestly, at 90%: with no model I'm more character than intellect. Noted — I'll answer in full once the engine is up."],
)

_KEYWORDS = {
    "greet": ("привет", "здравств", "здаров", "hello", "hi ", "hey", "доброе", "добрый"),
    "how_are_you": ("как дела", "как ты", "how are you", "how's it", "как сам"),
    "name": ("как тебя зовут", "твоё имя", "твое имя", "your name", "who are you", "кто ты"),
    "thanks": ("спасибо", "благодар", "thank", "thanks"),
    "bye": ("пока", "до встреч", "прощай", "bye", "goodbye", "see you"),
}


class OfflineBrain(Brain):
    name = "offline"

    def available(self) -> bool:
        return True

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        last = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                last = m.get("content", "")
                break
        ru = _is_russian(last)
        low = last.lower()

        for intent, words in _KEYWORDS.items():
            if any(w in low for w in words):
                ru_list, en_list = _RESPONSES[intent]
                return random.choice(ru_list if ru else en_list)

        return random.choice(_FALLBACK[0] if ru else _FALLBACK[1])
