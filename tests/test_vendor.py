"""What the vendor publishes."""

import pytest

from hibiscus_image.vendor import checksum, is_newer, latest_release, package_name, package_url

PAGE = """
<a href="releases/hibiscus-server-2.12.4.zip">hibiscus-server-2.12.4.zip</a>
<a href="releases/hibiscus-server-2.12.4.zip.asc">PGP</a>
<a href="releases/hibiscus-server-2.12.4.zip.SHA-256">SHA-256</a>
<a href='./releases/hibiscus-server-2.12.10.zip'>older layout</a>
<a href="releases/nightly/hibiscus-server-2.13.0-nightly.zip">nightly</a>
"""


def test_the_newest_linked_release_counts_and_the_nightly_does_not():
	assert latest_release(PAGE) == "2.12.10"


def test_a_page_without_release_is_an_error():
	with pytest.raises(ValueError, match="links no release"):
		latest_release('<a href="releases/nightly/hibiscus-server-2.13.0-nightly.zip">')


def test_newer_compares_by_number():
	assert is_newer("2.12.10", "2.12.4")
	assert not is_newer("2.12.4", "2.12.4")
	assert not is_newer("2.11.9", "2.12.4")


def test_names_and_addresses_of_a_package():
	assert package_name("2.12.4") == "hibiscus-server-2.12.4.zip"
	assert (
		package_url("2.12.4")
		== "https://www.willuhn.de/products/hibiscus-server/releases/hibiscus-server-2.12.4.zip"
	)


def test_reads_the_checksum_file_of_the_vendor():
	text = "FFE6D7E149F03F2E60915592A35D9872A0660C01338F97167D5EB5866C6EC3BB *hibiscus-server-2.12.4.zip\n"
	assert checksum(text, "2.12.4") == "ffe6d7e149f03f2e60915592a35d9872a0660c01338f97167d5eb5866c6ec3bb"
	assert checksum(text.replace("*", " "), "2.12.4") == checksum(text, "2.12.4")


@pytest.mark.parametrize(
	("text", "message"),
	[
		("nonsense", "does not hold one line"),
		(f"{'a' * 64} *hibiscus-server-2.12.3.zip", "names hibiscus-server-2.12.3.zip"),
	],
)
def test_refuses_another_checksum_file(text, message):
	with pytest.raises(ValueError, match=message):
		checksum(text, "2.12.4")
