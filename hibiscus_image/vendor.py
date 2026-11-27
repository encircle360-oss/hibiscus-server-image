"""What the vendor publishes: the releases on the download page and the checksum files.

The vendor has no machine-readable version file. The download page links the current release as
releases/hibiscus-server-<version>.zip next to its .asc and .SHA-256 files, and a nightly build under
releases/nightly/, which does not count.
"""

from __future__ import annotations

import re

DOWNLOAD_PAGE = "https://www.willuhn.de/products/hibiscus-server/download.php"
RELEASES = "https://www.willuhn.de/products/hibiscus-server/releases/"

_RELEASE_LINK = re.compile(
	r"""href=["'](?:\./)?releases/hibiscus-server-(?P<version>[0-9]+\.[0-9]+\.[0-9]+)\.zip["']"""
)
_CHECKSUM = re.compile(r"(?P<sha256>[0-9a-fA-F]{64}) [ *](?P<name>\S+)")


def _key(version: str) -> tuple[int, ...]:
	return tuple(int(number) for number in version.split("."))


def latest_release(page: str) -> str:
	"""The newest release that the download page links."""
	versions = {match["version"] for match in _RELEASE_LINK.finditer(page)}
	if not versions:
		raise ValueError("the download page links no release hibiscus-server-<version>.zip")
	return max(versions, key=_key)


def is_newer(candidate: str, pinned: str) -> bool:
	"""Whether a version comes after the pinned one."""
	return _key(candidate) > _key(pinned)


def package_name(version: str) -> str:
	"""The file name of the release package of a version."""
	return f"hibiscus-server-{version}.zip"


def package_url(version: str) -> str:
	"""The address of the release package of a version."""
	return RELEASES + package_name(version)


def checksum(text: str, version: str) -> str:
	"""The SHA-256 in the vendor's .SHA-256 file of a version, in lower case."""
	match = _CHECKSUM.fullmatch(text.strip())
	if match is None:
		raise ValueError("the .SHA-256 file does not hold one line '<sha256> *<file name>'")
	if match["name"] != package_name(version):
		raise ValueError(f"the .SHA-256 file names {match['name']}, not {package_name(version)}")
	return match["sha256"].lower()
