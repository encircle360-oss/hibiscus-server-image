#!/usr/bin/env bash
# Verify the PGP signature of a Hibiscus Server release package of the vendor.
#
#   scripts/verify-package.sh                         the package the Dockerfile pins
#   scripts/verify-package.sh --version V --sha256 S  another package, such as a new release
#   scripts/verify-package.sh --dir DIR               use hibiscus-server-<V>.zip and .zip.asc from DIR
#   scripts/verify-package.sh --key FILE              use this key file instead of keys/ or the key server
#
# The key is taken from keys/hibiscus-server-signing-key.asc when the repository has that file, else from
# keys.openpgp.org by the pinned fingerprint. The script fails unless the key it got has exactly the pinned
# fingerprint, the signature of the package is good and made by that key, and the package has the expected
# SHA-256.
set -euo pipefail

# Published by the vendor on its download pages; keys.openpgp.org serves the key under it.
readonly FINGERPRINT=7314FBDE7D38EE5610D291B65A8ED9CFC0DB6C70
readonly KEY_SERVER=https://keys.openpgp.org/vks/v1/by-fingerprint
readonly RELEASES=https://www.willuhn.de/products/hibiscus-server/releases

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

root=$(cd "$(dirname "$0")/.." && pwd)
version=""
sha256=""
dir=""
key=""

while [[ $# -gt 0 ]]; do
	case "$1" in
	--version)
		version=$(value_of "$@")
		shift 2
		;;
	--sha256)
		sha256=$(value_of "$@")
		shift 2
		;;
	--dir)
		dir=$(value_of "$@")
		shift 2
		;;
	--key)
		key=$(value_of "$@")
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

for tool in gpg gpgv curl sha256sum; do
	if ! command -v "$tool" >/dev/null; then
		echo "scripts/verify-package.sh needs $tool" >&2
		exit 1
	fi
done

if [[ -z "$version" ]]; then
	version=$(sed -n 's/^ARG HIBISCUS_VERSION=//p' "$root/Dockerfile")
fi
if [[ -z "$sha256" ]]; then
	sha256=$(sed -n 's/^ARG HIBISCUS_SHA256=//p' "$root/Dockerfile")
fi
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ || ! "$sha256" =~ ^[0-9a-f]{64}$ ]]; then
	echo "need a version like 2.12.4 and a SHA-256 in lower-case hex" >&2
	exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GNUPGHOME="$work/gnupg"
mkdir -m 0700 "$GNUPGHOME"

package="hibiscus-server-$version.zip"
if [[ -z "$dir" ]]; then
	dir="$work/download"
	mkdir "$dir"
	for file in "$package" "$package.asc"; do
		curl --fail --silent --show-error --location --retry 3 --output "$dir/$file" "$RELEASES/$file"
	done
fi

if [[ -n "$key" ]]; then
	source="the file $key"
elif [[ -f "$root/keys/hibiscus-server-signing-key.asc" ]]; then
	key="$root/keys/hibiscus-server-signing-key.asc"
	source="the repository (keys/hibiscus-server-signing-key.asc)"
else
	key="$work/key.asc"
	curl --fail --silent --show-error --location --retry 3 --output "$key" "$KEY_SERVER/$FINGERPRINT"
	source="keys.openpgp.org"
fi
echo "key from $source"

# The primary keys and user IDs in the key file, without importing it anywhere but here.
gpg --batch --with-colons --import-options show-only --import "$key" >"$work/key.txt" 2>/dev/null
primaries=$(awk -F: '$1 == "pub" { primary = 1; next } $1 == "fpr" && primary { print $10; primary = 0 }' "$work/key.txt")
uids=$(grep -c '^uid:' "$work/key.txt" || true)
echo "primary keys in the key file: ${primaries:-none}, user IDs: $uids"
if [[ "$primaries" != "$FINGERPRINT" ]]; then
	echo "::error::the key file must hold exactly the key $FINGERPRINT"
	exit 1
fi

# GnuPG cannot use a key without user ID, and keys.openpgp.org hands out user IDs only that their owner has
# confirmed. sqv from Sequoia checks such a key as well; it prints the fingerprint of the signing key.
if [[ $uids -eq 0 ]]; then
	if ! command -v sqv >/dev/null; then
		echo "::error::the key has no user ID, which gpgv cannot use; install sqv or add the key with its user ID as keys/hibiscus-server-signing-key.asc"
		exit 1
	fi
	echo "the key has no user ID, checking with $(sqv --version | head -n 1)"
	if ! signer=$(sqv --keyring "$key" "$dir/$package.asc" "$dir/$package"); then
		echo "::error::the signature of $package is not good"
		exit 1
	fi
else
	gpg --batch --yes --dearmor --output "$work/keyring.gpg" "$key"
	if ! gpgv --keyring "$work/keyring.gpg" --status-fd 3 "$dir/$package.asc" "$dir/$package" 3>"$work/status" 2>"$work/gpgv.log"; then
		cat "$work/gpgv.log" >&2
		echo "::error::the signature of $package is not good"
		exit 1
	fi
	cat "$work/gpgv.log"
	# VALIDSIG <signing key> <date> <time> <expiry> <version> <reserved> <algorithm> <hash> <class> <primary key>
	signer=$(awk '$2 == "VALIDSIG" { print $12 }' "$work/status")
fi
if [[ "$signer" != "$FINGERPRINT" ]]; then
	echo "::error::$package is signed by ${signer:-no key}, not by $FINGERPRINT"
	exit 1
fi
echo "good signature of $package by $signer"

actual=$(sha256sum "$dir/$package" | cut -d' ' -f1)
if [[ "$actual" != "$sha256" ]]; then
	echo "::error::$package has the SHA-256 $actual, not $sha256"
	exit 1
fi
echo "$package has the SHA-256 $sha256"
