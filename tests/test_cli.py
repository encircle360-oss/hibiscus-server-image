"""The command line."""

import hashlib
import json
import runpy
import sys

import pytest

from hibiscus_image import sources
from hibiscus_image.__main__ import main
from tests.test_dockerfile import SHA, TEXT


@pytest.fixture
def dockerfile(tmp_path):
	path = tmp_path / "Dockerfile"
	path.write_text(TEXT, encoding="utf-8")
	return path


def run(dockerfile, *args):
	return main(["--dockerfile", str(dockerfile), *args])


def test_prints_the_pins(dockerfile, capsys):
	assert run(dockerfile, "version") == 0
	assert run(dockerfile, "sha256") == 0
	assert run(dockerfile, "package-url") == 0
	assert capsys.readouterr().out.splitlines() == [
		"2.12.4",
		SHA,
		"https://www.willuhn.de/products/hibiscus-server/releases/hibiscus-server-2.12.4.zip",
	]


def test_prints_the_tags(dockerfile, capsys):
	assert (
		run(
			dockerfile,
			"tags",
			"--ref",
			"refs/tags/v2.12.4-1",
			"--sha",
			"0123456",
			"--existing",
			"v2.12.4-1\nv2.13.0-1",
		)
		== 0
	)
	assert (
		capsys.readouterr().out
		== "ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1,ghcr.io/encircle360-oss/hibiscus-server:2.12.4\n"
	)


def test_release_check(dockerfile, capsys):
	assert run(dockerfile, "release-check", "--ref", "refs/tags/v2.12.4-1") == 0
	assert run(dockerfile, "release-check", "--ref", "refs/tags/v2.12.5-1") == 1
	assert "but the Dockerfile pins 2.12.4" in capsys.readouterr().err


def test_reads_saved_vendor_files(dockerfile, tmp_path, capsys):
	page = tmp_path / "download.php"
	page.write_bytes(b'<a href="releases/hibiscus-server-2.12.5.zip">\xe4</a>')
	sums = tmp_path / "sums"
	sums.write_text(f"{'c' * 64} *hibiscus-server-2.12.5.zip\n", encoding="ascii")
	assert run(dockerfile, "latest", str(page)) == 0
	assert run(dockerfile, "newer", str(page)) == 0
	assert run(dockerfile, "checksum", str(sums), "--version", "2.12.5") == 0
	assert capsys.readouterr().out.splitlines() == ["2.12.5", "2.12.5", "c" * 64]
	page.write_text('<a href="releases/hibiscus-server-2.12.4.zip">', encoding="utf-8")
	assert run(dockerfile, "newer", str(page)) == 0
	assert capsys.readouterr().out == ""


def test_bump_writes_the_dockerfile(dockerfile):
	assert run(dockerfile, "bump", "--version", "2.12.5", "--sha256", "d" * 64) == 0
	text = dockerfile.read_text(encoding="utf-8")
	assert "ARG HIBISCUS_VERSION=2.12.5\n" in text
	assert f"ARG HIBISCUS_SHA256={'d' * 64}\n" in text


def test_same_package_compares_version_and_checksum(dockerfile, tmp_path, capsys):
	other = tmp_path / "other"
	other.write_text(TEXT, encoding="utf-8")
	assert run(dockerfile, "same-package", str(other)) == 0
	other.write_text(TEXT.replace(SHA, "b" * 64), encoding="utf-8")
	assert run(dockerfile, "same-package", str(other)) == 1
	assert f"pins the package 2.12.4 with SHA-256 {'b' * 64}, but" in capsys.readouterr().err


def source_list(tmp_path, version="2.12.4"):
	path = tmp_path / "sources.json"
	path.write_text(
		json.dumps(
			{
				"package": {"version": version},
				"components": [
					{
						"name": "library",
						"kind": "file",
						"version": "1.0",
						"paths": ["lib/library-1.0.jar"],
						"license": "LGPL-2.1",
						"url": "https://repo.example.com/library-1.0-sources.jar",
						"sha256": hashlib.sha256(b"source").hexdigest(),
					}
				],
			}
		),
		encoding="utf-8",
	)
	return path


def test_sources_archive_writes_the_archive_of_a_release(dockerfile, tmp_path, monkeypatch, capsys):
	def fake_download(component, work, cache=None):
		path = work / component.member
		path.write_bytes(b"source")
		return path

	monkeypatch.setattr(sources, "download", fake_download)
	args = ["sources-archive", "--list", str(source_list(tmp_path)), "--output", str(tmp_path / "out")]
	assert run(dockerfile, *args, "--label", "v2.12.4-1") == 0
	assert capsys.readouterr().out == f"{tmp_path / 'out' / 'hibiscus-server-v2.12.4-1-sources.tar.gz'}\n"
	assert run(dockerfile, *args, "--label", "pr-7") == 0
	assert run(dockerfile, *args, "--label", "v2.12.5-1") == 1
	assert "names Hibiscus Server 2.12.5, but the Dockerfile pins 2.12.4" in capsys.readouterr().err


def test_sources_archive_refuses_a_list_of_another_package(dockerfile, tmp_path, capsys):
	args = ["sources-archive", "--list", str(source_list(tmp_path, "2.12.3")), "--output", str(tmp_path)]
	assert run(dockerfile, *args, "--label", "main") == 1
	assert "belongs to the package 2.12.3, but the Dockerfile pins 2.12.4" in capsys.readouterr().err


def test_sources_archive_names_a_failed_component(dockerfile, tmp_path, monkeypatch, capsys):
	def failing(component, work, cache=None):
		raise OSError("HTTP Error 404")

	monkeypatch.setattr(sources, "download", failing)
	args = ["sources-archive", "--list", str(source_list(tmp_path)), "--output", str(tmp_path)]
	assert run(dockerfile, *args, "--label", "main") == 1
	assert "the source of library 1.0 from https://repo.example.com/" in capsys.readouterr().err


def test_summary(dockerfile, capsys):
	assert run(dockerfile, "summary", "--tags", "a,b") == 0
	assert capsys.readouterr().out.endswith("- `a`\n- `b`\n")


def test_problems_end_with_exit_code_1(dockerfile, tmp_path, capsys):
	assert run(tmp_path / "missing", "version") == 1
	assert run(dockerfile, "bump", "--version", "2.13", "--sha256", SHA) == 1
	assert "is not a version" in capsys.readouterr().err


def test_runs_as_module(dockerfile, monkeypatch):
	monkeypatch.delitem(sys.modules, "hibiscus_image.__main__", raising=False)
	monkeypatch.setattr("sys.argv", ["hibiscus_image", "--dockerfile", str(dockerfile), "version"])
	with pytest.raises(SystemExit) as exit_:
		runpy.run_module("hibiscus_image", run_name="__main__")
	assert exit_.value.code == 0
