"""The source list and the source archive, with local git repositories and files in place of the network."""

import gzip
import hashlib
import io
import json
import ssl
import subprocess
import tarfile
import urllib.error
from email.message import Message
from pathlib import Path

import pytest

from hibiscus_image import sources
from hibiscus_image.sources import (
	FILE,
	GIT,
	Component,
	Missing,
	SourceError,
	SourceList,
	archive_name,
	build,
	check_package,
	download,
	fetch_git,
	manifest,
	parse,
	run_git,
)

SHA = "c" * 40


def git(directory: Path, *args: str) -> str:
	return run_git(["-c", "user.name=Test", "-c", "user.email=test@example.com", *args], directory).strip()


@pytest.fixture
def repository(tmp_path):
	"""A repository with two commits; the pinned one is not the tip, as for a nightly plugin."""
	path = tmp_path / "upstream"
	path.mkdir()
	git(path, "init", "--quiet")
	(path / "Plugin.java").write_text("class Plugin {}\n", encoding="utf-8")
	git(path, "add", ".")
	git(path, "commit", "--quiet", "-m", "first")
	pinned = git(path, "rev-parse", "HEAD")
	(path / "Later.java").write_text("class Later {}\n", encoding="utf-8")
	git(path, "add", ".")
	git(path, "commit", "--quiet", "-m", "second")
	return path, pinned


def git_component(origin: str, commit: str, name: str = "plugin") -> Component:
	return Component(
		name,
		GIT,
		"1.0.0-nightly",
		("plugins/plugin",),
		"GPL-2.0",
		origin,
		commit,
		ref="master",
		note="state of master at the build date",
	)


def file_component(content: bytes, name: str = "library") -> Component:
	return Component(
		name,
		FILE,
		"2.0",
		("lib/library-2.0.jar",),
		"LGPL-2.1",
		"https://repo.example.com/library-2.0-sources.jar",
		hashlib.sha256(content).hexdigest(),
		coordinates="org.example:library:2.0",
	)


def entry(**fields):
	base = {
		"name": "plugin",
		"kind": "git",
		"version": "1.0.0",
		"paths": ["plugins/plugin"],
		"license": "GPL-2.0",
		"repository": "https://github.com/example/plugin",
		"ref": "master",
		"commit": SHA,
	}
	return {key: value for key, value in (base | fields).items() if value is not None}


def text(*components, missing=(), version="2.12.4"):
	return json.dumps(
		{"package": {"version": version}, "components": list(components), "missing": list(missing)}
	)


class Response(io.BytesIO):
	"""A response of urlopen."""


def opener_of(*outcomes):
	"""An opener that raises or answers in turn and records each request."""
	calls = []

	def opener(request, timeout):
		calls.append((request.full_url, timeout))
		outcome = outcomes[len(calls) - 1]
		if isinstance(outcome, BaseException):
			raise outcome
		return Response(outcome)

	return opener, calls


def http_error(code, retry_after=None):
	headers = Message()
	if retry_after is not None:
		headers["Retry-After"] = retry_after
	return urllib.error.HTTPError("https://repo.example.com/x", code, "error", headers, None)


def members(path: Path) -> dict[str, tarfile.TarInfo]:
	with tarfile.open(path) as tar:
		return {info.name: info for info in tar.getmembers()}


def test_parse_reads_git_and_file_components():
	listed = parse(
		text(
			entry(note="state of master at the build date"),
			entry(
				name="h2",
				kind="file",
				version="2.3.232",
				repository=None,
				ref=None,
				commit=None,
				url="https://repo1.maven.org/maven2/h2-2.3.232-sources.jar",
				sha256="d" * 64,
				coordinates="com.h2database:h2:2.3.232",
			),
			missing=[
				{"name": "libct", "paths": ["lib/libct.so"], "license": "proprietary", "reason": "none"}
			],
		)
	)
	assert listed.package_version == "2.12.4"
	plugin, h2 = listed.components
	assert (plugin.kind, plugin.origin, plugin.pin, plugin.ref, plugin.note) == (
		GIT,
		"https://github.com/example/plugin",
		SHA,
		"master",
		"state of master at the build date",
	)
	assert plugin.member == "plugin-1.0.0.tar"
	assert (h2.kind, h2.pin, h2.ref, h2.coordinates, h2.member) == (
		FILE,
		"d" * 64,
		"",
		"com.h2database:h2:2.3.232",
		"h2-2.3.232-sources.jar",
	)
	assert listed.missing == (Missing("libct", ("lib/libct.so",), "proprietary", "none"),)


@pytest.mark.parametrize(
	("data", "message"),
	[
		("{", "no valid JSON"),
		("[]", "must be an object"),
		(json.dumps({"package": {"version": "2.12"}, "components": [entry()]}), "package version"),
		(json.dumps({"package": "2.12.4", "components": [entry()]}), "package version"),
		(text(), "at least one component"),
		(
			json.dumps({"package": {"version": "2.12.4"}, "components": [entry()], "missing": {}}),
			"missing must",
		),
		(text("x"), "component 0: must be an object"),
		(text(entry(kind="svn")), "component plugin: kind must be"),
		(text(entry(name=None, kind="svn")), "component 0: kind must be"),
		(text(entry(extra=1)), "unknown fields extra"),
		(text(entry(name="../x")), "name '../x' may hold only"),
		(text(entry(version="1 0")), "version '1 0' may hold only"),
		(text(entry(license="")), "license must be a non-empty string"),
		(text(entry(paths=[])), "paths must list"),
		(text(entry(paths=["a", 1])), "paths must list"),
		(text(entry(repository="http://github.com/example/plugin")), "is not an https address"),
		(text(entry(commit="abc")), "is not a full commit SHA"),
		(text(entry(ref=None)), "ref must be"),
		(
			text(
				entry(kind="file", repository=None, ref=None, commit=None, url="https://x", sha256="E" * 64)
			),
			"is not a SHA-256",
		),
		(text(entry(), entry()), "names plugin more than once"),
		(text(entry(), missing=["x"]), "missing 0: must be an object"),
		(text(entry(), missing=[{"name": "x", "paths": ["a"], "license": "GPL"}]), "missing x: reason"),
		(text(entry(), missing=[{"paths": ["a"]}]), "missing 0: name"),
	],
)
def test_parse_refuses_a_list_it_cannot_trust(data, message):
	with pytest.raises(ValueError, match=message):
		parse(data)


def test_the_list_must_belong_to_the_pinned_package():
	listed = parse(text(entry()))
	check_package(listed, "2.12.4")
	with pytest.raises(
		ValueError, match=r"belongs to the package 2\.12\.4, but the Dockerfile pins 2\.12\.5"
	):
		check_package(listed, "2.12.5")


def test_fetch_git_archives_exactly_the_pinned_commit(repository, tmp_path):
	path, pinned = repository
	work = tmp_path / "work"
	work.mkdir()
	archive = fetch_git(git_component(path.as_uri(), pinned), work)
	assert archive.name == "plugin-1.0.0-nightly.tar"
	with tarfile.open(archive) as tar:
		names = tar.getnames()
	assert "plugin-1.0.0-nightly/Plugin.java" in names
	assert "plugin-1.0.0-nightly/Later.java" not in names


def test_fetch_git_refuses_another_commit(tmp_path):
	def fake(args, cwd):
		return "f" * 40 + "\n" if args[0] == "rev-parse" else ""

	with pytest.raises(ValueError, match=f"fetched {'f' * 40} instead of the pinned commit {SHA}"):
		fetch_git(git_component("https://example.com/r", SHA), tmp_path, git=fake)


def test_fetch_git_tries_again_and_then_gives_up(tmp_path):
	calls, pauses = [], []

	def flaky(args, cwd):
		calls.append(args[0])
		if args[0] == "fetch" and calls.count("fetch") < 3:
			raise OSError("unable to access the repository")
		return SHA if args[0] == "rev-parse" else ""

	fetch_git(git_component("https://example.com/r", SHA), tmp_path, git=flaky, sleep=pauses.append)
	assert calls == ["init", "fetch", "fetch", "fetch", "rev-parse", "archive"]
	assert pauses == [15, 30]

	def broken(args, cwd):
		if args[0] == "fetch":
			raise OSError("unable to access the repository")
		return ""

	with pytest.raises(OSError, match="unable to access"):
		fetch_git(
			git_component("https://example.com/r", SHA, "other"),
			tmp_path,
			git=broken,
			attempts=2,
			sleep=pauses.append,
		)


def test_run_git_reports_the_message_of_git(tmp_path):
	assert run_git(["--version"], tmp_path).startswith("git version")
	with pytest.raises(OSError, match="not a git repository"):
		run_git(["rev-parse", "HEAD"], tmp_path)


def test_run_git_names_the_exit_code_when_git_says_nothing(tmp_path, monkeypatch):
	monkeypatch.setattr(
		sources.subprocess, "run", lambda *args, **kwargs: subprocess.CompletedProcess(args, 3, "", "")
	)
	with pytest.raises(OSError, match="git fetch failed with exit code 3"):
		run_git(["fetch"], tmp_path)


def test_run_git_ignores_the_configuration_of_the_user(tmp_path, monkeypatch):
	config = tmp_path / "gitconfig"
	config.write_text(
		'[url "https://elsewhere.example/"]\n\tinsteadOf = https://example.com/\n', encoding="utf-8"
	)
	monkeypatch.setenv("GIT_CONFIG_GLOBAL", str(config))
	assert "elsewhere" not in run_git(["ls-remote", "--get-url", "https://example.com/r"], tmp_path)


def test_download_checks_the_pinned_sha256(tmp_path):
	opener, calls = opener_of(b"source")
	path = download(file_component(b"source"), tmp_path, opener=opener)
	assert path.read_bytes() == b"source"
	assert calls == [("https://repo.example.com/library-2.0-sources.jar", 60)]
	opener, _ = opener_of(b"changed")
	with pytest.raises(ValueError, match="not the pinned"):
		download(file_component(b"source"), tmp_path, opener=opener)


def test_download_waits_when_the_server_throttles(tmp_path):
	pauses = []
	opener, calls = opener_of(
		http_error(429, "7"),
		http_error(503),
		urllib.error.URLError("reset"),
		http_error(429, "900"),
		b"source",
	)
	download(file_component(b"source"), tmp_path, opener=opener, sleep=pauses.append)
	assert len(calls) == 5
	assert pauses == [7.0, 20.0, 30.0, 120.0]


def test_download_gives_up(tmp_path):
	opener, calls = opener_of(http_error(404))
	with pytest.raises(urllib.error.HTTPError):
		download(file_component(b"source"), tmp_path, opener=opener, sleep=lambda _: None)
	assert len(calls) == 1
	opener, _ = opener_of(http_error(429), http_error(429))
	with pytest.raises(urllib.error.HTTPError):
		download(file_component(b"source"), tmp_path, opener=opener, attempts=2, sleep=lambda _: None)
	opener, _ = opener_of(TimeoutError(), TimeoutError())
	with pytest.raises(TimeoutError):
		download(file_component(b"source"), tmp_path, opener=opener, attempts=2, sleep=lambda _: None)


def test_download_does_not_try_again_when_the_certificate_fails(tmp_path):
	opener, calls = opener_of(
		urllib.error.URLError(ssl.SSLCertVerificationError("unable to get local issuer"))
	)
	with pytest.raises(urllib.error.URLError, match="local issuer"):
		download(file_component(b"source"), tmp_path, opener=opener, sleep=lambda _: None)
	assert len(calls) == 1


def test_download_keeps_checked_files_in_the_cache(tmp_path):
	cache = tmp_path / "cache"
	component = file_component(b"source")
	opener, calls = opener_of(b"source")
	(tmp_path / "a").mkdir()
	download(component, tmp_path / "a", cache=cache, opener=opener)
	assert (cache / component.pin).read_bytes() == b"source"
	(tmp_path / "b").mkdir()
	assert download(component, tmp_path / "b", cache=cache, opener=opener).read_bytes() == b"source"
	assert len(calls) == 1
	(cache / component.pin).write_bytes(b"poisoned")
	opener, calls = opener_of(b"source")
	(tmp_path / "c").mkdir()
	assert download(component, tmp_path / "c", cache=cache, opener=opener).read_bytes() == b"source"
	assert len(calls) == 1
	assert (cache / component.pin).read_bytes() == b"source"


def test_archive_names_follow_the_release(tmp_path):
	assert archive_name("v2.12.4-1") == "hibiscus-server-v2.12.4-1-sources.tar.gz"
	with pytest.raises(ValueError, match="is not a release tag or label"):
		archive_name("v2/../x")


def source_list(repository, content=b"source"):
	path, pinned = repository
	return SourceList(
		"2.12.4",
		(file_component(content), git_component(path.as_uri(), pinned)),
		(Missing("libct", ("plugins/hibiscus/lib/libct.so",), "proprietary", "no source is published"),),
	)


def fake_fetch(content):
	def fetch(component, work):
		path = work / component.member
		path.write_bytes(content)
		return path

	return fetch


def test_build_writes_one_archive_with_every_component_and_a_manifest(repository, tmp_path):
	notes = tmp_path / "SOURCES.md"
	notes.write_text("# Quelltext\n", encoding="utf-8")
	archive = build(
		source_list(repository), "v2.12.4-1", tmp_path / "out", notes=notes, fetch=fake_fetch(b"source")
	)
	assert archive == tmp_path / "out" / "hibiscus-server-v2.12.4-1-sources.tar.gz"
	top = "hibiscus-server-v2.12.4-1-sources"
	infos = members(archive)
	assert list(infos) == [
		f"{top}/SOURCES.md",
		f"{top}/components/library-2.0-sources.jar",
		f"{top}/components/plugin-1.0.0-nightly.tar",
		f"{top}/manifest.json",
	]
	assert {(info.mtime, info.mode, info.uid, info.gid, info.uname) for info in infos.values()} == {
		(0, 0o644, 0, 0, "")
	}
	with tarfile.open(archive) as tar:
		data = json.loads(tar.extractfile(f"{top}/manifest.json").read())
		library = tar.extractfile(f"{top}/components/library-2.0-sources.jar").read()
	assert library == b"source"
	assert data["package"] == "hibiscus-server-2.12.4.zip"
	assert data["release"] == "v2.12.4-1"
	library_entry, plugin_entry = data["components"]
	assert library_entry == {
		"name": "library",
		"version": "2.0",
		"paths": ["lib/library-2.0.jar"],
		"license": "LGPL-2.1",
		"kind": "file",
		"url": "https://repo.example.com/library-2.0-sources.jar",
		"sha256": hashlib.sha256(b"source").hexdigest(),
		"coordinates": "org.example:library:2.0",
		"file": "components/library-2.0-sources.jar",
		"file_sha256": hashlib.sha256(b"source").hexdigest(),
	}
	assert plugin_entry["commit"] == repository[1]
	assert plugin_entry["ref"] == "master"
	assert plugin_entry["note"] == "state of master at the build date"
	assert "coordinates" not in plugin_entry
	assert data["missing"] == [
		{
			"name": "libct",
			"paths": ["plugins/hibiscus/lib/libct.so"],
			"license": "proprietary",
			"reason": "no source is published",
		}
	]


def test_build_gives_the_same_archive_for_the_same_sources(repository, tmp_path):
	first = build(source_list(repository), "main", tmp_path / "a", fetch=fake_fetch(b"source"))
	second = build(source_list(repository), "main", tmp_path / "b", fetch=fake_fetch(b"source"))
	assert first.read_bytes() == second.read_bytes()
	with gzip.open(first) as stream:
		assert stream.read(1)
	assert first.read_bytes()[4:8] == b"\0\0\0\0"


def test_build_names_the_component_that_fails(repository, tmp_path):
	def changed(component, work):
		return download(component, work, opener=opener_of(b"changed")[0])

	with pytest.raises(
		SourceError, match=r"the source of library 2\.0 from https://repo\.example\.com/.*not the pinned"
	):
		build(source_list(repository), "main", tmp_path / "out", fetch=changed)

	def unreachable(component, work):
		raise OSError("could not resolve host")

	with pytest.raises(SourceError, match=r"plugin 1\.0\.0-nightly .* failed: could not resolve host"):
		build(source_list(repository), "main", tmp_path / "out", git=unreachable, fetch=fake_fetch(b"source"))


def test_build_downloads_through_the_cache_by_default(repository, tmp_path, monkeypatch):
	seen = []

	def fake_download(component, work, cache=None):
		seen.append(cache)
		return fake_fetch(b"source")(component, work)

	monkeypatch.setattr(sources, "download", fake_download)
	build(source_list(repository), "main", tmp_path / "out", cache=tmp_path / "cache")
	assert seen == [tmp_path / "cache"]


def test_manifest_lists_members_in_name_order(tmp_path):
	path = tmp_path / "f"
	path.write_bytes(b"x")
	listed = SourceList("2.12.4", (), ())
	files = {
		"components/b": (file_component(b"x", "b"), path),
		"components/a": (file_component(b"x", "a"), path),
	}
	assert [item["name"] for item in json.loads(manifest(listed, "main", files))["components"]] == ["a", "b"]
