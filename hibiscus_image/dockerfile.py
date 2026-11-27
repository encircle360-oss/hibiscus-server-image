"""The pins of the Dockerfile: the vendor package and the base images."""

from __future__ import annotations

import re
from pathlib import Path

DOCKERFILE = Path("Dockerfile")

_VERSION = re.compile(r"^ARG HIBISCUS_VERSION=(?P<value>\S+)$", re.MULTILINE)
_SHA256 = re.compile(r"^ARG HIBISCUS_SHA256=(?P<value>\S+)$", re.MULTILINE)
_FROM = re.compile(r"^FROM\s+(?P<image>\S+)", re.MULTILINE)
VERSION_FORMAT = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
SHA256_FORMAT = re.compile(r"[0-9a-f]{64}")


def _single(pattern: re.Pattern[str], text: str, name: str) -> re.Match[str]:
	matches = list(pattern.finditer(text))
	if len(matches) != 1:
		raise ValueError(f"the Dockerfile must set {name} exactly once with ARG {name}=<value>")
	return matches[0]


def hibiscus_version(text: str) -> str:
	"""The version of the vendor package, such as 2.12.4."""
	value = _single(_VERSION, text, "HIBISCUS_VERSION")["value"]
	if VERSION_FORMAT.fullmatch(value) is None:
		raise ValueError(f"HIBISCUS_VERSION {value!r} is not a version like 2.12.4")
	return value


def hibiscus_sha256(text: str) -> str:
	"""The SHA-256 of the vendor package that the build accepts."""
	value = _single(_SHA256, text, "HIBISCUS_SHA256")["value"]
	if SHA256_FORMAT.fullmatch(value) is None:
		raise ValueError(f"HIBISCUS_SHA256 {value!r} is not a SHA-256 in lower-case hex")
	return value


def base_images(text: str) -> list[str]:
	"""The image of every FROM, in order."""
	return [match["image"] for match in _FROM.finditer(text)]


def split_digest(image: str) -> tuple[str, str | None]:
	"""An image reference split into name with tag and digest, if it has one."""
	name, _, digest = image.partition("@")
	return name, digest or None


def bump(text: str, version: str, sha256: str) -> str:
	"""The Dockerfile with another vendor package."""
	if VERSION_FORMAT.fullmatch(version) is None:
		raise ValueError(f"{version!r} is not a version like 2.12.4")
	if SHA256_FORMAT.fullmatch(sha256) is None:
		raise ValueError(f"{sha256!r} is not a SHA-256 in lower-case hex")
	version_match = _single(_VERSION, text, "HIBISCUS_VERSION")
	text = text[: version_match.start("value")] + version + text[version_match.end("value") :]
	sha_match = _single(_SHA256, text, "HIBISCUS_SHA256")
	return text[: sha_match.start("value")] + sha256 + text[sha_match.end("value") :]
