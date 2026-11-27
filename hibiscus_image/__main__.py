"""Command line of the tools: python -m hibiscus_image <command>."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from hibiscus_image import dockerfile, tags, vendor
from hibiscus_image.summary import summary


def _parser() -> argparse.ArgumentParser:
	parser = argparse.ArgumentParser(prog="python -m hibiscus_image", description=__doc__)
	parser.add_argument(
		"--dockerfile", type=Path, default=dockerfile.DOCKERFILE, help="the Dockerfile to read"
	)
	commands = parser.add_subparsers(dest="command", required=True)

	commands.add_parser("version", help="print the pinned version of Hibiscus Server")
	commands.add_parser("sha256", help="print the pinned SHA-256 of the vendor package")
	commands.add_parser("package-url", help="print the address of the pinned vendor package")

	tag = commands.add_parser("tags", help="print the image tags of a git ref, separated by commas")
	tag.add_argument("--ref", required=True)
	tag.add_argument("--sha", required=True)
	tag.add_argument("--existing", default="", help="the release tags of the repository, one per line")

	check = commands.add_parser("release-check", help="check a release tag against the Dockerfile")
	check.add_argument("--ref", required=True)

	latest = commands.add_parser("latest", help="print the newest release on a saved download page")
	latest.add_argument("page", type=Path)

	newer = commands.add_parser(
		"newer", help="print the newest release on a saved download page if it is newer"
	)
	newer.add_argument("page", type=Path)

	checksum = commands.add_parser(
		"checksum", help="print the SHA-256 of a saved .SHA-256 file of the vendor"
	)
	checksum.add_argument("file", type=Path)
	checksum.add_argument("--version", required=True)

	bump = commands.add_parser("bump", help="pin another vendor package in the Dockerfile")
	bump.add_argument("--version", required=True)
	bump.add_argument("--sha256", required=True)

	summarize = commands.add_parser("summary", help="print the summary of a build as Markdown")
	summarize.add_argument("--tags", default="", help="the image tags, separated by commas")
	return parser


def main(argv: Sequence[str] | None = None) -> int:
	"""Run one command; problems end with exit code 1 and a message on stderr."""
	args = _parser().parse_args(argv)
	try:
		text = args.dockerfile.read_text(encoding="utf-8")
		match args.command:
			case "version":
				print(dockerfile.hibiscus_version(text))
			case "sha256":
				print(dockerfile.hibiscus_sha256(text))
			case "package-url":
				print(vendor.package_url(dockerfile.hibiscus_version(text)))
			case "tags":
				print(",".join(tags.image_tags(args.ref, args.sha, args.existing.split())))
			case "release-check":
				problems = tags.release_check(
					args.ref.removeprefix(tags.TAG_PREFIX), dockerfile.hibiscus_version(text)
				)
				for problem in problems:
					print(problem, file=sys.stderr)
				return 1 if problems else 0
			case "latest":
				print(vendor.latest_release(args.page.read_text(encoding="utf-8", errors="replace")))
			case "newer":
				release = vendor.latest_release(args.page.read_text(encoding="utf-8", errors="replace"))
				if vendor.is_newer(release, dockerfile.hibiscus_version(text)):
					print(release)
			case "checksum":
				print(vendor.checksum(args.file.read_text(encoding="ascii"), args.version))
			case "bump":
				args.dockerfile.write_text(dockerfile.bump(text, args.version, args.sha256), encoding="utf-8")
			case _:
				print(summary(text, [tag for tag in args.tags.split(",") if tag]), end="")
	except (OSError, ValueError) as error:
		print(error, file=sys.stderr)
		return 1
	return 0


if __name__ == "__main__":
	sys.exit(main())
