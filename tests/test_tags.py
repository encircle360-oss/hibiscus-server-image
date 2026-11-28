"""The image tags and the scheme of the release tags."""

import pytest

from hibiscus_image.tags import (
	IMAGE,
	Release,
	image_tags,
	is_release,
	parse_release,
	release_check,
	releases,
)


def names(tags):
	return [tag.removeprefix(f"{IMAGE}:") for tag in tags]


def test_main_gets_main_and_the_commit():
	assert names(image_tags("refs/heads/main", "0123456789abcdef")) == ["main", "main-0123456"]


def test_main_needs_a_commit_sha():
	with pytest.raises(ValueError, match="not a commit SHA"):
		image_tags("refs/heads/main", "HEAD")


@pytest.mark.parametrize(
	"ref", ["refs/heads/feature", "refs/pull/1/merge", "refs/tags/2.12.4-1", "refs/tags/v2.12.4-0"]
)
def test_other_refs_get_no_tags(ref):
	assert image_tags(ref, "0123456") == ()


def test_the_first_release_gets_every_moving_tag():
	assert names(image_tags("refs/tags/v2.12.4-1", "0123456")) == ["2.12.4-1", "2.12.4", "2", "latest"]


def test_moving_tags_only_go_forward():
	existing = ["v2.12.4-1", "v2.12.4-2", "v2.13.0-1", "v3.0.0-1", "not-a-release"]
	assert names(image_tags("refs/tags/v2.12.4-3", "0", existing)) == ["2.12.4-3", "2.12.4"]
	assert names(image_tags("refs/tags/v2.12.4-1", "0", existing)) == ["2.12.4-1"]
	assert names(image_tags("refs/tags/v2.13.0-2", "0", existing)) == ["2.13.0-2", "2.13.0", "2"]
	assert names(image_tags("refs/tags/v3.0.0-2", "0", existing)) == ["3.0.0-2", "3.0.0", "3", "latest"]


def test_versions_compare_by_number():
	assert Release("2.12.10", 1).is_newer_than(Release("2.12.9", 5))
	assert not Release("2.12.4", 1).is_newer_than(Release("2.12.4", 1))
	assert releases(["v2.12.4-1", "x"]) == (Release("2.12.4", 1),)


def test_is_release_knows_the_scheme():
	assert is_release("v2.12.4-1")
	assert not is_release("v2.12.4-0")
	assert not is_release("main")


def test_parse_release_explains_the_scheme():
	with pytest.raises(ValueError, match=r"like v2\.12\.4-1"):
		parse_release("v2.12.4")


def test_release_check_requires_the_pinned_version():
	assert release_check("v2.12.4-1", "2.12.4") == []
	assert release_check("v2.12.5-1", "2.12.4") == [
		"the release v2.12.5-1 names Hibiscus Server 2.12.5, but the Dockerfile pins 2.12.4"
	]
	assert release_check("v2.12.4", "2.12.4") == [
		"'v2.12.4' is not a release tag like v2.12.4-1 (the revision starts at 1)"
	]
