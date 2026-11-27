"""The summary of a build."""

from hibiscus_image.summary import summary
from tests.test_dockerfile import SHA, TEXT


def test_names_package_images_and_platforms():
	text = summary(TEXT)
	assert text.startswith("## Hibiscus Server 2.12.4\n")
	assert f"`{SHA}`" in text
	assert "eclipse-temurin:21-jdk-noble@sha256:" in text
	assert "`linux/amd64`, `linux/arm64`" in text
	assert "Image-Tags" not in text


def test_lists_the_tags():
	assert summary(TEXT, ["ghcr.io/x:1", "ghcr.io/x:latest"]).endswith(
		"- `ghcr.io/x:1`\n- `ghcr.io/x:latest`\n"
	)
