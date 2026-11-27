"""The pins of the Dockerfile."""

import pytest

from hibiscus_image.dockerfile import base_images, bump, hibiscus_sha256, hibiscus_version, split_digest

SHA = "a" * 64
TEXT = f"""ARG HIBISCUS_VERSION=2.12.4
ARG HIBISCUS_SHA256={SHA}

FROM eclipse-temurin:21-jdk-noble@sha256:{"1" * 64} AS build
ARG HIBISCUS_VERSION
FROM gcr.io/distroless/java21-debian12:nonroot@sha256:{"2" * 64}
"""


def test_reads_the_vendor_package():
	assert hibiscus_version(TEXT) == "2.12.4"
	assert hibiscus_sha256(TEXT) == SHA


def test_reads_every_from():
	assert base_images(TEXT) == [
		f"eclipse-temurin:21-jdk-noble@sha256:{'1' * 64}",
		f"gcr.io/distroless/java21-debian12:nonroot@sha256:{'2' * 64}",
	]


def test_splits_the_digest_off():
	assert split_digest("a:1@sha256:00") == ("a:1", "sha256:00")
	assert split_digest("a:1") == ("a:1", None)


@pytest.mark.parametrize(
	("text", "message"),
	[
		("", "exactly once"),
		("ARG HIBISCUS_VERSION=1.0.0\nARG HIBISCUS_VERSION=1.0.1\n", "exactly once"),
		("ARG HIBISCUS_VERSION=2.12\n", "not a version"),
	],
)
def test_refuses_a_missing_or_odd_version(text, message):
	with pytest.raises(ValueError, match=message):
		hibiscus_version(text)


def test_refuses_an_odd_checksum():
	with pytest.raises(ValueError, match="not a SHA-256"):
		hibiscus_sha256("ARG HIBISCUS_SHA256=ABC\n")


def test_bump_changes_only_the_two_pins():
	bumped = bump(TEXT, "2.12.5", "b" * 64)
	assert hibiscus_version(bumped) == "2.12.5"
	assert hibiscus_sha256(bumped) == "b" * 64
	assert bumped.replace("2.12.5", "2.12.4").replace("b" * 64, SHA) == TEXT


@pytest.mark.parametrize(("version", "sha256"), [("2.13", SHA), ("2.13.0", "B" * 64)])
def test_bump_refuses_odd_values(version, sha256):
	with pytest.raises(ValueError, match="is not a"):
		bump(TEXT, version, sha256)
