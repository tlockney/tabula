"""Persistent mock-device identity and credential (a JSON file, 0600)."""

from __future__ import annotations

import json
import os
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path


@dataclass
class DeviceStore:
    path: Path
    device_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    refresh_token: str | None = None
    ha_device_id: str | None = None
    settings_revision: int = 0
    settings: dict = field(default_factory=dict)

    @classmethod
    def load(cls, path: Path) -> DeviceStore:
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
            return cls(path=path, **data)
        store = cls(path=path)
        store.save()
        return store

    def save(self) -> None:
        data = {k: v for k, v in asdict(self).items() if k != "path"}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        tmp.replace(self.path)

    @property
    def paired(self) -> bool:
        return self.refresh_token is not None
