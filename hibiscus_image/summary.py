"""The summary of a build for workflow runs and release notes."""

from __future__ import annotations

from collections.abc import Sequence

from hibiscus_image.dockerfile import base_images, hibiscus_sha256, hibiscus_version
from hibiscus_image.vendor import package_name, package_url

PLATFORMS = ("linux/amd64", "linux/arm64")


def summary(dockerfile: str, tags: Sequence[str] = ()) -> str:
	"""Markdown with the vendor package, the base images and the tags of a build."""
	version = hibiscus_version(dockerfile)
	build, runtime = base_images(dockerfile)
	lines = [
		f"## Hibiscus Server {version}",
		"",
		"| | |",
		"|---|---|",
		f"| Paket | [{package_name(version)}]({package_url(version)}) |",
		f"| SHA-256 des Pakets | `{hibiscus_sha256(dockerfile)}` |",
		f"| Build-Image | `{build}` |",
		f"| Laufzeit-Image | `{runtime}` |",
		f"| Plattformen | {', '.join(f'`{platform}`' for platform in PLATFORMS)} |",
	]
	if tags:
		lines += ["", "Image-Tags:", "", *(f"- `{tag}`" for tag in tags)]
	return "\n".join(lines) + "\n"
