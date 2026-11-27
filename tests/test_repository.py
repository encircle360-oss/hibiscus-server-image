"""Checks of the files of this repository, so that a change to one of them cannot leave the others behind."""

import re
from pathlib import Path

import pytest

from hibiscus_image.dockerfile import base_images, hibiscus_sha256, hibiscus_version, split_digest

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")
WORKFLOWS = sorted((ROOT / ".github").rglob("*.yml"))
FINGERPRINT = "7314FBDE7D38EE5610D291B65A8ED9CFC0DB6C70"


def read(name: str) -> str:
	return (ROOT / name).read_text(encoding="utf-8")


def hook_revisions() -> dict[str, str]:
	"""The rev of each hook repository in .pre-commit-config.yaml, where rev follows repo directly."""
	text = read(".pre-commit-config.yaml")
	return dict(re.findall(r"^\s*- repo: *(\S+) *\n\s*rev: *['\"]?([^'\"\s]+)['\"]? *$", text, re.MULTILINE))


def development_pins() -> dict[str, str]:
	"""The exact versions of requirements-dev.txt by package."""
	return dict(re.findall(r"^([A-Za-z0-9._-]+)==(\S+)$", read("requirements-dev.txt"), re.MULTILINE))


def test_the_dockerfile_pins_a_valid_package():
	assert hibiscus_version(DOCKERFILE)
	assert hibiscus_sha256(DOCKERFILE)


def test_every_base_image_is_pinned_by_digest():
	images = base_images(DOCKERFILE)
	assert len(images) == 2
	for image in images:
		_, digest = split_digest(image)
		assert digest is not None and re.fullmatch(r"sha256:[0-9a-f]{64}", digest), image


def test_the_build_stage_is_the_jdk_of_the_launcher_tests():
	# tests/launcher/run.sh and tests/image/run.sh find the JDK image by this line.
	assert re.search(r"^FROM \S+@sha256:[0-9a-f]{64} AS build$", DOCKERFILE, re.MULTILINE)


@pytest.mark.parametrize(
	("hook", "tool"),
	[
		("https://github.com/astral-sh/ruff-pre-commit", "ruff"),
		("https://github.com/zizmorcore/zizmor-pre-commit", "zizmor"),
	],
)
def test_the_hooks_run_the_versions_that_requirements_dev_pins(hook, tool):
	# The CI runs ruff and zizmor from requirements-dev.txt, pre-commit from the hooks; a version that moves
	# in only one of them fails here.
	assert hook_revisions()[hook] == f"v{development_pins()[tool]}"


def test_every_action_is_pinned_by_commit_with_its_version():
	assert WORKFLOWS
	for path in WORKFLOWS:
		for line in path.read_text(encoding="utf-8").splitlines():
			match = re.match(r"^\s*(?:- )?uses: *(\S+)(.*)$", line)
			if match is None or match[1].startswith(("./", "$/")):
				continue
			assert re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", match[1]), f"{path.name}: {line.strip()}"
			assert re.fullmatch(r" +# v\S+", match[2]), (
				f"{path.name}: {line.strip()} needs its version as a comment"
			)


@pytest.mark.parametrize("name", ["README.md", "SOURCES.md", "docs/architektur.md"])
def test_the_documentation_names_the_pinned_package(name):
	# A new package changes the bundled components; the documentation follows by hand and is reviewed.
	assert f"hibiscus-server-{hibiscus_version(DOCKERFILE)}.zip" in read(name)


@pytest.mark.parametrize("name", ["scripts/verify-package.sh", "README.md", "docs/sicherheit.md"])
def test_the_signing_key_has_one_fingerprint(name):
	text = read(name).replace(" ", "")
	assert FINGERPRINT in text
	assert not re.search(r"[0-9A-F]{40}", text.replace(FINGERPRINT, ""))
