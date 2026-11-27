#!/usr/bin/env bash
# Build the image locally with the same inputs as the CI.
#
#   ./build.sh                          build hibiscus-server:local for linux/amd64
#   ./build.sh --tag TAG                tag the image as TAG instead
#   ./build.sh --push                   push the image to the registry of TAG instead of loading it
#   ./build.sh --platform PLATFORM      build for another platform than linux/amd64
set -euo pipefail

usage() {
	sed -n '4,7s/^#   //p' "$0"
}

value_of() {
	if [[ $# -lt 2 || "$2" == --* ]]; then
		echo "$1 needs a value" >&2
		exit 1
	fi
	printf '%s' "$2"
}

tag="hibiscus-server:local"
platform="linux/amd64"
output="--load"

while [[ $# -gt 0 ]]; do
	case "$1" in
	--tag)
		tag=$(value_of "$@")
		shift 2
		;;
	--push)
		output="--push"
		shift
		;;
	--platform)
		platform=$(value_of "$@")
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "unknown option: $1" >&2
		usage >&2
		exit 1
		;;
	esac
done

cd "$(dirname "$0")"

# A download of the sources without git history builds as version dev.
version=$(git describe --tags --always --dirty 2>/dev/null || echo dev)

echo "building $tag ($version) for $platform"
docker buildx build --platform "$platform" --build-arg "VERSION=$version" --file Dockerfile --tag "$tag" "$output" .
