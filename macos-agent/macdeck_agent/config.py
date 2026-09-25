"""Paths and settings. Other modules read these at call time (config.X), so tests can
point them at a temporary folder."""
import os
from pathlib import Path

PORT = int(os.environ.get("MACDECK_PORT", "8765"))
HERE = Path(__file__).resolve().parent.parent  # macos-agent/
PROFILES_PATH = HERE / "profiles.json"
TOKEN_PATH = HERE / ".token"
CERT_PATH = HERE / ".cert.pem"
KEY_PATH = HERE / ".key.pem"
PINS_PATH = HERE / "pins.json"
ORDER_PATH = HERE / "home_order.json"
LOGOS_DIR = HERE / "logos"
APP_ID = "com.example.macdeck"  # the Android app, for USB pairing

# What this agent supports, announced in every layout so the phone never has to guess.
FEATURES = ["home", "pins", "reorder", "images"]
