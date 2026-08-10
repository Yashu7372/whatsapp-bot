from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

EXPRESSIONS = ["idle", "talking", "curious", "surprised", "laughing", "thinking"]
CHARACTERS = ["bhaiya", "chitti"]


@dataclass
class Character:
    name: str
    display_name: str
    expressions: dict[str, Path] = field(default_factory=dict)

    def get_expression(self, emotion: str) -> Path | None:
        for candidate in [emotion, "idle", "talking"]:
            p = self.expressions.get(candidate)
            if p and p.exists():
                return p
        return next((p for p in self.expressions.values() if p.exists()), None)


@dataclass
class CharacterPack:
    pack_root: Path
    characters: dict[str, Character] = field(default_factory=dict)
    background_path: Path | None = None

    def is_ready(self) -> bool:
        return (
            bool(self.background_path and self.background_path.exists())
            and all(bool(ch.expressions) for ch in self.characters.values())
        )

    def get_character(self, name: str) -> Character | None:
        return self.characters.get(name)

    def status_dict(self) -> dict:
        return {
            "ready": self.is_ready(),
            "background": bool(self.background_path and self.background_path.exists()),
            "characters": {
                name: {
                    "expressions": sorted(ch.expressions.keys()),
                    "missing": [e for e in EXPRESSIONS if e not in ch.expressions],
                }
                for name, ch in self.characters.items()
            },
        }
