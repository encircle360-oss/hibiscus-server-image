"""Checks of the files of this repository, so that a change to one of them cannot leave the others behind."""

import re
from pathlib import Path

import pytest
import yaml

from hibiscus_image.dockerfile import base_images, hibiscus_sha256, hibiscus_version, split_digest
from hibiscus_image.sources import FILE, GIT, parse

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")
WORKFLOWS = sorted((ROOT / ".github").rglob("*.yml"))
FINGERPRINT = "7314FBDE7D38EE5610D291B65A8ED9CFC0DB6C70"
SOURCE_LIST = parse((ROOT / "sources.json").read_text(encoding="utf-8"))


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


_LEVELS = {"none": 0, "read": 1, "write": 2}


def scopes(permissions: object, all_scopes: set[str]) -> dict[str, int]:
	"""A permissions block as the level of each scope; read-all and write-all cover every scope."""
	if isinstance(permissions, str):
		level = _LEVELS[permissions.removesuffix("-all")]
		return dict.fromkeys(all_scopes, level)
	return {scope: _LEVELS[level] for scope, level in (permissions or {}).items()}


def workflow(path: Path) -> dict:
	return yaml.safe_load(path.read_text(encoding="utf-8"))


def local_calls():
	"""Every job that calls a workflow of this repository: caller, job name, the job and the called file."""
	for path in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
		for name, job in workflow(path)["jobs"].items():
			uses = job.get("uses", "")
			if uses.startswith(("./", "$/")):
				yield path, name, job, ROOT / uses[2:]


def test_the_release_and_the_attach_run_call_the_workflows_they_need():
	calls = {(path.name, called.name) for path, _, _, called in local_calls()}
	assert {("release.yml", "sources.yml"), ("sources-attach.yml", "sources.yml")} <= calls


def test_a_called_workflow_requests_no_more_permissions_than_its_caller_grants():
	# GitHub checks the permissions of every job of a called workflow when the caller starts, also of jobs
	# whose condition would skip them; a job that asks for more makes the caller fail before it runs.
	all_scopes = {
		"contents",
		"packages",
		"id-token",
		"attestations",
		"security-events",
		"pull-requests",
		"issues",
	}
	for path, name, job, called in local_calls():
		caller = workflow(path)
		granted = scopes(job.get("permissions", caller.get("permissions")), all_scopes)
		target = workflow(called)
		for inner_name, inner in target["jobs"].items():
			requested = scopes(inner.get("permissions", target.get("permissions")), all_scopes)
			for scope, level in requested.items():
				assert level <= granted.get(scope, 0), (
					f"{path.name} job {name} grants {scope} {granted.get(scope, 0)}, but {called.name} job "
					f"{inner_name} requests {level}"
				)


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


def table_rows(name: str) -> list[str]:
	return [line for line in read(name).splitlines() if line.startswith("| ") and not line.startswith("|---")]


def cells(row: str) -> list[str]:
	return [cell.strip() for cell in row.strip().strip("|").split("|")]


def test_the_source_list_belongs_to_the_pinned_package():
	# A new package needs a new source list; the list cannot follow the Dockerfile without a review.
	version = hibiscus_version(DOCKERFILE)
	assert SOURCE_LIST.package_version == version
	by_name = {component.name: component for component in SOURCE_LIST.components}
	for name in ("hibiscus", "hibiscus.server"):
		# The vendor keeps a branch per version instead of a tag.
		assert (by_name[name].version, by_name[name].ref) == (version, version), name


def test_every_file_of_the_source_list_names_its_version_in_its_address():
	for component in SOURCE_LIST.components:
		if component.kind == FILE:
			assert component.version in component.origin, component.name
			assert not component.coordinates or component.coordinates.endswith(f":{component.version}")


def test_sources_md_names_every_git_component_with_version_ref_and_commit():
	rows = table_rows("SOURCES.md")
	for component in (component for component in SOURCE_LIST.components if component.kind == GIT):
		matching = [row for row in rows if f"]({component.origin})" in row]
		assert len(matching) == 1, f"SOURCES.md needs one row that links {component.origin}"
		row = cells(matching[0])
		assert component.version in row[1], f"{component.name}: version {component.version}"
		assert f"`{component.ref}`" in row[3], f"{component.name}: ref {component.ref}"
		assert f"`{component.pin}`" in row[4], f"{component.name}: commit {component.pin}"


def test_sources_md_names_every_file_component_with_its_version():
	rows = table_rows("SOURCES.md")
	for component in (component for component in SOURCE_LIST.components if component.kind == FILE):
		key = f"`{component.coordinates}`" if component.coordinates else f"]({component.origin})"
		matching = [row for row in rows if key in row]
		assert len(matching) == 1, f"SOURCES.md needs one row with {key}"
		assert component.version in cells(matching[0])[2], f"{component.name}: version {component.version}"


def test_sources_md_names_nothing_that_the_source_list_lacks():
	text = read("SOURCES.md")
	listed = {component.coordinates for component in SOURCE_LIST.components if component.coordinates}
	assert set(re.findall(r"`([\w.-]+:[\w.-]+:[\w.-]+)`", text)) == listed
	commits = {component.pin for component in SOURCE_LIST.components if component.kind == GIT}
	assert set(re.findall(r"`([0-9a-f]{40})`", text)) == commits


def test_sources_md_names_what_the_archive_lacks():
	text = read("SOURCES.md")
	for gap in SOURCE_LIST.missing:
		assert f"`{gap.name}`" in text, gap.name
		for path in gap.paths:
			assert f"`{path}`" in text, f"{gap.name}: {path}"
