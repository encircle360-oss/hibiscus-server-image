#!/usr/bin/env bash
# Test the entry point launcher/HibiscusLauncher.java and enforce full line and branch coverage.
#
#   tests/launcher/run.sh
#
# Compiles and tests inside the JDK image of the build stage of the Dockerfile, pinned by digest, without
# network and as the calling user. JUnit and JaCoCo are loaded once into .cache/ and checked by SHA-256.
# Reports go to build/launcher-tests/: the JaCoCo report as HTML and CSV.
set -euo pipefail

readonly JUNIT_VERSION=6.1.3
readonly JUNIT_SHA256=e62b96ac475dbcde8599ea905d088f65d90778f86e259b856a49fa5c4ea256ec
readonly JACOCO_VERSION=0.8.16
readonly JACOCO_AGENT_SHA256=37ae2f374ad77e85c59c6e5b46565226c3ac3cb460ac19de0bcb844ac2f43e33
readonly JACOCO_CLI_SHA256=1f2a806225623fbd9f53d1ee0e42a6975323de0adebe01c12fc464ed147d756a
readonly MAVEN=https://repo1.maven.org/maven2

for tool in docker curl sha256sum; do
	if ! command -v "$tool" >/dev/null; then
		echo "tests/launcher/run.sh needs $tool" >&2
		exit 1
	fi
done

root=$(cd "$(dirname "$0")/../.." && pwd)
cache="$root/.cache/launcher-tests"
build="$root/build/launcher-tests"
mkdir -p "$cache"
rm -rf "$build"
mkdir -p "$build"

# fetch NAME PATH SHA256: load a jar from Maven Central once and check it every time.
fetch() {
	local name=$1 path=$2 sha256=$3
	if [[ ! -f "$cache/$name" ]]; then
		curl --fail --silent --show-error --location --retry 8 --retry-max-time 300 \
			--output "$cache/$name.part" "$MAVEN/$path"
		mv "$cache/$name.part" "$cache/$name"
	fi
	if ! echo "$sha256  $cache/$name" | sha256sum --check --strict --quiet; then
		rm -f "$cache/$name"
		echo "$name does not have the pinned SHA-256" >&2
		exit 1
	fi
}

fetch junit.jar \
	"org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar" \
	"$JUNIT_SHA256"
fetch jacocoagent.jar "org/jacoco/org.jacoco.agent/$JACOCO_VERSION/org.jacoco.agent-$JACOCO_VERSION-runtime.jar" \
	"$JACOCO_AGENT_SHA256"
fetch jacococli.jar "org/jacoco/org.jacoco.cli/$JACOCO_VERSION/org.jacoco.cli-$JACOCO_VERSION-nodeps.jar" \
	"$JACOCO_CLI_SHA256"

# The JDK of the build stage, so the tests compile with the compiler that builds the image.
jdk=$(sed -n 's/^FROM \([^ ]*@sha256:[0-9a-f]*\) AS build$/\1/p' "$root/Dockerfile")
if [[ -z "$jdk" ]]; then
	echo "the Dockerfile has no build stage pinned by digest" >&2
	exit 1
fi

docker run --rm --network none --user "$(id -u):$(id -g)" --env HOME=/tmp \
	--volume "$root:/src:ro" --volume "$cache:/tools:ro" --volume "$build:/build" \
	--workdir /src "$jdk" bash -euo pipefail -c '
		t=tests/launcher
		javac --release 21 -Xlint:all -Werror -d /build/main launcher/HibiscusLauncher.java
		javac --release 21 -Xlint:all -Werror -d /build/stubs $(find "$t/stubs" -name "*.java")
		javac --release 21 -Xlint:all -Werror -cp /build/main:/build/stubs:/tools/junit.jar -d /build/test \
			$(find "$t/src" -name "*.java")
		javac --release 21 -Xlint:all -Werror -cp /build/test -d /build/driver $(find "$t/driver" -name "*.java")
		jar --create --file /build/fakedb.jar -C /build/driver .

		java -javaagent:/tools/jacocoagent.jar=destfile=/build/jacoco.exec,includes=HibiscusLauncher* \
			-Dfakedb.jar=/build/fakedb.jar -jar /tools/junit.jar execute \
			--class-path /build/main:/build/stubs:/build/test --scan-class-path /build/test \
			--fail-if-no-tests --disable-banner --details=summary --details-theme=ascii

		java -jar /tools/jacococli.jar report /build/jacoco.exec --classfiles /build/main --sourcefiles launcher \
			--csv /build/coverage.csv --html /build/coverage --name HibiscusLauncher --quiet
	'

# Columns of the CSV: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,
# LINE_MISSED,LINE_COVERED,...
awk -F, 'NR > 1 { bm += $6; bc += $7; lm += $8; lc += $9 }
	END {
		printf "coverage of the entry point: lines %d/%d, branches %d/%d\n", lc, lc + lm, bc, bc + bm
		if (lm + bm > 0) {
			print "the entry point needs full line and branch coverage; see build/launcher-tests/coverage/index.html" > "/dev/stderr"
			exit 1
		}
	}' "$build/coverage.csv"
