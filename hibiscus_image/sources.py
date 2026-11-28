"""The source code of the software in the image and the source archive that every release attaches.

sources.json pins one entry per component of the vendor package: a git commit of its repository, or a
file such as a -sources.jar from Maven Central by its SHA-256. The archive holds each component as one
member and a manifest that names version, origin and pin of every member and what the archive lacks.

A git component is fetched by its commit and checked against it, so git's content addressing is the check
of its content; a file is checked against its pinned SHA-256.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import shutil
import ssl
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path

SOURCES = Path("sources.json")
GIT = "git"
FILE = "file"
MANIFEST = "manifest.json"

_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
_COMMIT = re.compile(r"[0-9a-f]{40}")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_FIELDS = {
	GIT: {"repository", "ref", "commit"},
	FILE: {"url", "sha256"},
}
_COMMON = {"name", "kind", "version", "paths", "license"}
_OPTIONAL = {GIT: {"note"}, FILE: {"coordinates", "note"}}
# Maven Central answers 429 when a runner fetches too often; 5xx and these errors pass on their own.
_TRANSIENT = {408, 429, 500, 502, 503, 504}
_MAX_WAIT = 120
_BLOCK = 1 << 20


class SourceError(Exception):
	"""A component whose source cannot be fetched or does not match its pin."""


@dataclass(frozen=True)
class Component:
	"""One component of the package and the pinned origin of its source."""

	name: str
	kind: str
	version: str
	paths: tuple[str, ...]
	license: str
	origin: str
	pin: str
	ref: str = ""
	coordinates: str = ""
	note: str = ""

	@property
	def member(self) -> str:
		"""The file name of the component in the archive."""
		if self.kind == GIT:
			return f"{self.name}-{self.version}.tar"
		return f"{self.name}-{self.version}-sources.jar"


@dataclass(frozen=True)
class Missing:
	"""A part of the package whose source the archive does not hold, and why."""

	name: str
	paths: tuple[str, ...]
	license: str
	reason: str


@dataclass(frozen=True)
class SourceList:
	"""The pinned sources of one vendor package."""

	package_version: str
	components: tuple[Component, ...]
	missing: tuple[Missing, ...]


def _text(entry: dict[str, object], key: str, where: str) -> str:
	value = entry.get(key)
	if not isinstance(value, str) or not value.strip():
		raise ValueError(f"{where}: {key} must be a non-empty string")
	return value


def _paths(entry: dict[str, object], where: str) -> tuple[str, ...]:
	value = entry.get("paths")
	if not isinstance(value, list) or not value or not all(isinstance(path, str) and path for path in value):
		raise ValueError(f"{where}: paths must list the files or directories of the package")
	return tuple(value)


def _component(entry: object, index: int) -> Component:
	if not isinstance(entry, dict):
		raise ValueError(f"component {index}: must be an object")
	name = entry.get("name")
	where = f"component {name}" if isinstance(name, str) and name else f"component {index}"
	kind = _text(entry, "kind", where)
	if kind not in _FIELDS:
		raise ValueError(f"{where}: kind must be {GIT!r} or {FILE!r}, not {kind!r}")
	unknown = set(entry) - _COMMON - _FIELDS[kind] - _OPTIONAL[kind]
	if unknown:
		raise ValueError(f"{where}: unknown fields {', '.join(sorted(unknown))}")
	name = _text(entry, "name", where)
	version = _text(entry, "version", where)
	for label, value in (("name", name), ("version", version)):
		if _NAME.fullmatch(value) is None:
			raise ValueError(f"{where}: {label} {value!r} may hold only letters, digits, '.', '_' and '-'")
	origin = _text(entry, "repository" if kind == GIT else "url", where)
	if not origin.startswith("https://"):
		raise ValueError(f"{where}: {origin!r} is not an https address")
	pin = _text(entry, "commit" if kind == GIT else "sha256", where)
	pattern, what = (
		(_COMMIT, "a full commit SHA") if kind == GIT else (_SHA256, "a SHA-256 in lower-case hex")
	)
	if pattern.fullmatch(pin) is None:
		raise ValueError(f"{where}: {pin!r} is not {what}")
	return Component(
		name=name,
		kind=kind,
		version=version,
		paths=_paths(entry, where),
		license=_text(entry, "license", where),
		origin=origin,
		pin=pin,
		ref=_text(entry, "ref", where) if kind == GIT else "",
		coordinates=str(entry.get("coordinates", "")),
		note=str(entry.get("note", "")),
	)


def _missing(entry: object, index: int) -> Missing:
	if not isinstance(entry, dict):
		raise ValueError(f"missing {index}: must be an object")
	where = f"missing {entry.get('name') or index}"
	return Missing(
		name=_text(entry, "name", where),
		paths=_paths(entry, where),
		license=_text(entry, "license", where),
		reason=_text(entry, "reason", where),
	)


def parse(text: str) -> SourceList:
	"""The source list in sources.json, checked field by field."""
	try:
		data = json.loads(text)
	except json.JSONDecodeError as error:
		raise ValueError(f"the source list is no valid JSON: {error}") from error
	if not isinstance(data, dict):
		raise ValueError("the source list must be an object with package, components and missing")
	package = data.get("package")
	version = package.get("version") if isinstance(package, dict) else None
	if not isinstance(version, str) or _VERSION.fullmatch(version) is None:
		raise ValueError("the source list must name the package version like 2.12.4 in package.version")
	entries = data.get("components")
	if not isinstance(entries, list) or not entries:
		raise ValueError("the source list must have at least one component")
	missing = data.get("missing", [])
	if not isinstance(missing, list):
		raise ValueError("missing must be a list")
	components = tuple(_component(entry, index) for index, entry in enumerate(entries))
	names = [component.name for component in components]
	duplicates = sorted({name for name in names if names.count(name) > 1})
	if duplicates:
		raise ValueError(f"the source list names {', '.join(duplicates)} more than once")
	return SourceList(
		version, components, tuple(_missing(entry, index) for index, entry in enumerate(missing))
	)


def check_package(source_list: SourceList, hibiscus_version: str) -> None:
	"""Refuse a source list that belongs to another vendor package than the Dockerfile pins."""
	if source_list.package_version != hibiscus_version:
		raise ValueError(
			f"the source list belongs to the package {source_list.package_version}, "
			f"but the Dockerfile pins {hibiscus_version}"
		)


# The fetch must reach the pinned address and nothing else, so git runs without the user's and the
# system's configuration, which may rewrite addresses or add credentials.
_GIT_ENVIRONMENT = {
	"GIT_CONFIG_GLOBAL": os.devnull,
	"GIT_CONFIG_NOSYSTEM": "1",
	"GIT_TERMINAL_PROMPT": "0",
	"LC_ALL": "C",
}

Git = Callable[[Sequence[str], Path], str]


def run_git(args: Sequence[str], cwd: Path) -> str:
	"""Run git in a directory and return its output; a failure raises with git's message."""
	result = subprocess.run(  # noqa: S603 # the arguments are fixed or checked by parse
		[GIT, *args],
		cwd=cwd,
		env={**os.environ, **_GIT_ENVIRONMENT},
		capture_output=True,
		text=True,
		check=False,
	)
	if result.returncode != 0:
		raise OSError(result.stderr.strip() or f"git {args[0]} failed with exit code {result.returncode}")
	return result.stdout


def fetch_git(
	component: Component,
	work: Path,
	*,
	git: Git = run_git,
	attempts: int = 4,
	sleep: Callable[[float], None] = time.sleep,
) -> Path:
	"""The tar of a component's repository at its pinned commit, after checking that git fetched that commit."""
	repository = work / f"{component.name}.git"
	repository.mkdir()
	git(["init", "--quiet", "--bare"], repository)
	attempt = 1
	while True:
		try:
			git(
				["fetch", "--quiet", "--depth", "1", "--no-tags", component.origin, component.pin], repository
			)
			break
		except OSError:
			if attempt == attempts:
				raise
			sleep(15 * attempt)
			attempt += 1
	fetched = git(["rev-parse", "--verify", "FETCH_HEAD^{commit}"], repository).strip()
	if fetched != component.pin:
		raise ValueError(f"git fetched {fetched or 'nothing'} instead of the pinned commit {component.pin}")
	archive = work / component.member
	prefix = f"{component.name}-{component.version}/"
	git(["archive", "--format=tar", f"--prefix={prefix}", f"--output={archive}", component.pin], repository)
	return archive


Opener = Callable[..., object]


def _wait(error: urllib.error.HTTPError, attempt: int) -> float:
	"""The pause before the next attempt: the server's Retry-After in seconds if it sends one, else a growing pause."""
	header = (error.headers.get("Retry-After") or "").strip() if error.headers else ""
	if header.isdigit():
		return min(float(header), _MAX_WAIT)
	return min(10.0 * attempt, _MAX_WAIT)


def _sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as stream:
		while block := stream.read(_BLOCK):
			digest.update(block)
	return digest.hexdigest()


def download(
	component: Component,
	work: Path,
	*,
	cache: Path | None = None,
	opener: Opener = urllib.request.urlopen,
	attempts: int = 8,
	sleep: Callable[[float], None] = time.sleep,
) -> Path:
	"""A component's file, from the cache or the network, after checking its pinned SHA-256."""
	target = work / component.member
	cached = cache / component.pin if cache else None
	if cached and cached.is_file() and _sha256(cached) == component.pin:
		shutil.copyfile(cached, target)
		return target
	request = urllib.request.Request(component.origin, headers={"User-Agent": "hibiscus-server-image"})  # noqa: S310 # parse allows https only
	attempt = 1
	while True:
		try:
			with opener(request, timeout=60) as response, target.open("wb") as stream:
				shutil.copyfileobj(response, stream, _BLOCK)
			break
		except urllib.error.HTTPError as error:
			if error.code not in _TRANSIENT or attempt == attempts:
				raise
			sleep(_wait(error, attempt))
		except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
			# A certificate that does not verify stays so; trying again only delays the failure.
			certificate = isinstance(getattr(error, "reason", None), ssl.SSLError)
			if certificate or attempt == attempts:
				raise
			sleep(min(10.0 * attempt, _MAX_WAIT))
		attempt += 1
	actual = _sha256(target)
	if actual != component.pin:
		raise ValueError(f"the file has the SHA-256 {actual}, not the pinned {component.pin}")
	if cached:
		cached.parent.mkdir(parents=True, exist_ok=True)
		shutil.copyfile(target, cached)
	return target


def _entry(component: Component, member: str, sha256: str) -> dict[str, object]:
	entry: dict[str, object] = {
		"name": component.name,
		"version": component.version,
		"paths": list(component.paths),
		"license": component.license,
		"kind": component.kind,
	}
	if component.kind == GIT:
		entry |= {"repository": component.origin, "ref": component.ref, "commit": component.pin}
	else:
		entry |= {"url": component.origin, "sha256": component.pin}
		if component.coordinates:
			entry["coordinates"] = component.coordinates
	if component.note:
		entry["note"] = component.note
	return entry | {"file": member, "file_sha256": sha256}


def manifest(source_list: SourceList, label: str, files: dict[str, tuple[Component, Path]]) -> str:
	"""The manifest of an archive: the package, each member with origin and pin, and what is missing."""
	return (
		json.dumps(
			{
				"package": f"hibiscus-server-{source_list.package_version}.zip",
				"release": label,
				"components": [
					_entry(component, member, _sha256(path))
					for member, (component, path) in sorted(files.items())
				],
				"missing": [
					{"name": gap.name, "paths": list(gap.paths), "license": gap.license, "reason": gap.reason}
					for gap in source_list.missing
				],
			},
			indent=2,
			ensure_ascii=False,
		)
		+ "\n"
	)


def archive_name(label: str) -> str:
	"""The file name of the source archive of a release, such as hibiscus-server-v2.12.4-1-sources.tar.gz."""
	if _NAME.fullmatch(label) is None:
		raise ValueError(f"{label!r} is not a release tag or label of letters, digits, '.', '_' and '-'")
	return f"hibiscus-server-{label}-sources.tar.gz"


def _add(tar: tarfile.TarFile, name: str, path: Path) -> None:
	# Fixed owner, mode and time, so that the same sources give the same archive.
	info = tarfile.TarInfo(name)
	info.size = path.stat().st_size
	info.mode = 0o644
	info.mtime = 0
	with path.open("rb") as stream:
		tar.addfile(info, stream)


def write_archive(output: Path, top: str, files: Iterable[tuple[str, Path]]) -> None:
	"""A tar.gz of files under one top-level directory, in name order and without times or owners."""
	with (
		output.open("wb") as raw,
		gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed,
		tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar,
	):
		for name, path in sorted(files):
			_add(tar, f"{top}/{name}", path)


def build(
	source_list: SourceList,
	label: str,
	output: Path,
	*,
	notes: Path | None = None,
	cache: Path | None = None,
	git: Callable[[Component, Path], Path] = fetch_git,
	fetch: Callable[[Component, Path], Path] | None = None,
) -> Path:
	"""Fetch and check every source of the list and write the archive into the output directory."""
	name = archive_name(label)
	fetch = fetch or (lambda component, work: download(component, work, cache=cache))
	output.mkdir(parents=True, exist_ok=True)
	with tempfile.TemporaryDirectory(prefix="sources-") as temporary:
		work = Path(temporary)
		files: dict[str, tuple[Component, Path]] = {}
		for component in sorted(source_list.components, key=lambda component: component.name):
			try:
				path = git(component, work) if component.kind == GIT else fetch(component, work)
			except (OSError, ValueError) as error:
				raise SourceError(
					f"the source of {component.name} {component.version} from {component.origin} "
					f"({component.pin}) failed: {error}"
				) from error
			files[f"components/{component.member}"] = (component, path)
		manifest_path = work / MANIFEST
		manifest_path.write_text(manifest(source_list, label, files), encoding="utf-8")
		members = [(member, path) for member, (_, path) in files.items()] + [(MANIFEST, manifest_path)]
		if notes is not None:
			members.append((notes.name, notes))
		target = output / name
		write_archive(target, name.removesuffix(".tar.gz"), members)
	return target
