"""Smoke tests for the TARS core: no network, no API keys, no extra deps.

These guard the pieces that must keep working as we grow toward Android:
the always-on offline brain, language detection, the model manager's
device-aware recommendation, and the hybrid brain wiring.
"""
import os
import tempfile
import unittest
from pathlib import Path

from tars import models
from tars.brains import build_brain
from tars.brains.offline import OfflineBrain
from tars.config import load_settings
from tars.core import Tars


class OfflineBrainTests(unittest.TestCase):
    def setUp(self):
        self.brain = OfflineBrain()

    def test_always_available(self):
        self.assertTrue(self.brain.available())

    def test_replies_in_russian_to_russian(self):
        out = self.brain.reply("sys", [{"role": "user", "content": "Привет, TARS"}])
        # A Cyrillic reply for a Cyrillic prompt.
        self.assertRegex(out, r"[А-Яа-яЁё]")

    def test_replies_in_english_to_english(self):
        out = self.brain.reply("sys", [{"role": "user", "content": "hello there"}])
        self.assertNotRegex(out, r"[А-Яа-яЁё]")

    def test_never_empty(self):
        out = self.brain.reply("sys", [{"role": "user", "content": "tell me about black holes"}])
        self.assertTrue(out.strip())


class ModelManagerTests(unittest.TestCase):
    def test_small_phone_gets_nothing(self):
        # The vision brain needs ~6 GB; smaller phones fall back to the offline brain.
        self.assertIsNone(models.recommend(1000))
        self.assertIsNone(models.recommend(4000))

    def test_galaxy_a32_6gb_gets_the_vl_3b(self):
        # The target device: ~6 GB RAM (reports ~5500 MB) lands on Qwen2.5-VL-3B.
        self.assertEqual(models.recommend(5500).id, "qwen2.5-vl-3b")

    def test_recommended_brain_can_see(self):
        # TARS is a vision robot — the recommended model is multimodal.
        self.assertTrue(models.recommend(5500).vision)

    def test_flagship_gets_the_heaviest(self):
        self.assertEqual(models.recommend(16000).id, "qwen2.5-vl-7b")

    def test_recommendation_is_monotonic(self):
        # More RAM never recommends a smaller model.
        order = [s.id for s in models.CATALOG]
        last = -1
        for ram in (2000, 4000, 8000, 16000, 32000):
            pick = models.recommend(ram)
            if pick:
                self.assertGreaterEqual(order.index(pick.id), last)
                last = order.index(pick.id)

    def test_catalog_urls_well_formed(self):
        for spec in models.CATALOG:
            self.assertTrue(spec.url.startswith("https://huggingface.co/"))
            self.assertTrue(spec.url.endswith(".gguf"))


class BrainWiringTests(unittest.TestCase):
    def test_offline_is_always_last_resort(self):
        settings = load_settings()
        brains = build_brain(settings).brains
        self.assertEqual(brains[-1].name, "offline")

    def test_respond_works_with_no_keys_and_no_network(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["TARS_MEMORY"] = str(Path(d) / "m.db")
            settings = load_settings()
            tars = Tars(settings)
            reply = tars.respond("Привет")
            self.assertTrue(reply.strip())


if __name__ == "__main__":
    unittest.main()
