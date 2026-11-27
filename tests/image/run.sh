#!/usr/bin/env bash
# Prove an image: start Hibiscus Server with the embedded database and with MariaDB, the way an operator
# runs it, and check authentication, XML-RPC, restarts and the refusals of the entry point.
#
#   tests/image/run.sh IMAGE        for example tests/image/run.sh hibiscus-server:local
#
# Every container runs as uid 65532 with a read-only root file system, all capabilities dropped and
# no-new-privileges. The passwords are generated for each run, live only in a temporary directory and are
# never printed. Every container, volume and network of the test is removed when it ends, and it fails after
# 15 minutes.
set -euo pipefail

readonly TIME_LIMIT=15m
readonly MARIADB_IMAGE=mariadb:11.8@sha256:e1d5495f445654134f226f55a41ce0da227116f6f27f283101f405c794d841fc
readonly CN=hibiscus.test
readonly PATHS=(/hibiscus/ /webadmin/ /xmlrpc/ /soap/ /sensors/)
readonly KONTO_FIND='<?xml version="1.0"?><methodCall><methodName>hibiscus.xmlrpc.konto.find</methodName><params/></methodCall>'

if [[ $# -ne 1 || -z "$1" ]]; then
	echo "usage: tests/image/run.sh IMAGE" >&2
	exit 1
fi
for tool in docker curl timeout base64 grep; do
	if ! command -v "$tool" >/dev/null; then
		echo "tests/image/run.sh needs $tool" >&2
		exit 1
	fi
done

if [[ -z "${IMAGE_TEST_UNDER_TIME_LIMIT:-}" ]]; then
	export IMAGE_TEST_UNDER_TIME_LIMIT=1
	exec timeout --kill-after=1m "$TIME_LIMIT" "$BASH" "$0" "$@"
fi

image=$1
root=$(cd "$(dirname "$0")/../.." && pwd)
# The JDK image of the build stage has a shell and find; it looks into the volumes.
helper=$(sed -n 's/^FROM \([^ ]*@sha256:[0-9a-f]*\) AS build$/\1/p' "$root/Dockerfile")
prefix="hibiscus-image-test-$$"
network="$prefix-net"
work=$(mktemp -d)
failures=()

step() {
	printf '\n==> %s\n' "$*"
}

fail() {
	printf '\nimage test failed: %s\n' "$*" >&2
	exit 1
}

check() {
	local what=$1
	shift
	if "$@"; then
		echo "ok: $what"
	else
		echo "FAILED: $what" >&2
		failures+=("$what")
	fi
}

cleanup() {
	local status=$?
	docker ps --all --quiet --filter "name=^$prefix-" | xargs --no-run-if-empty docker rm --force --volumes >/dev/null
	docker volume ls --quiet --filter "name=^$prefix-" | xargs --no-run-if-empty docker volume rm >/dev/null
	docker network rm "$network" >/dev/null 2>&1 || true
	rm -rf "$work"
	exit "$status"
}
trap cleanup EXIT

secret() {
	head -c 32 /dev/urandom | base64 | tr -d '/+=\n'
}

# Readable by uid 65532 in the containers; the directory is a throwaway of this run.
mkdir -p "$work/secrets" "$work/wrong" "$work/db"
chmod 0755 "$work" "$work/secrets" "$work/wrong" "$work/db"
secret > "$work/secrets/master-password"
secret > "$work/secrets/db-password"
secret > "$work/wrong/master-password"
secret > "$work/db/root-password"
cp "$work/secrets/db-password" "$work/db/user-password"
printf '[client]\nuser=root\npassword=%s\n' "$(cat "$work/db/root-password")" > "$work/db/client.cnf"
chmod 0444 "$work"/secrets/* "$work"/wrong/* "$work"/db/*

for who in good bad; do
	if [[ $who == good ]]; then pw="$work/secrets/master-password"; else pw="$work/wrong/master-password"; fi
	printf 'machine %s login admin password %s\n' "$CN" "$(cat "$pw")" > "$work/netrc-$who"
	chmod 0600 "$work/netrc-$who"
done

hardening=(
	--read-only --tmpfs "/tmp:rw,size=64m" --cap-drop ALL --security-opt no-new-privileges
	--memory 768m --env "HIBISCUS_CERT_HOSTNAME=$CN"
)

# start NAME VOLUME [docker run options]: start the image in the background with the test secrets.
start() {
	local name=$1 volume=$2
	shift 2
	docker run --detach --name "$prefix-$name" --network "$network" "${hardening[@]}" \
		--volume "$prefix-$volume:/data" --volume "$work/secrets:/run/secrets/hibiscus:ro" \
		--publish 127.0.0.1::8080 "$@" "$image" >/dev/null
}

# wait_ready NAME: until the entry point reports the plugins or the container ends.
wait_ready() {
	local container="$prefix-$1"
	for _ in {1..90}; do
		if docker logs "$container" 2>&1 | grep -q 'all plugins initialised'; then
			port=$(docker port "$container" 8080/tcp | head -n 1 | sed 's/.*://')
			for _ in {1..30}; do
				if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
					return 0
				fi
				sleep 1
			done
			fail "$container reports its plugins but does not answer on port 8080"
		fi
		if [[ $(docker inspect --format '{{.State.Running}}' "$container") != true ]]; then
			docker logs "$container" 2>&1 | tail -n 40 >&2
			fail "$container ended during the start"
		fi
		sleep 2
	done
	fail "$container did not start within three minutes"
}

# refused NAME CODE MESSAGE [docker run options]: the container ends with CODE and MESSAGE in its log.
refused() {
	local name=$1 code=$2 message=$3 status=0
	shift 3
	timeout 120 docker run --name "$prefix-$name" --network none "${hardening[@]}" \
		--volume "$work/secrets:/run/secrets/hibiscus:ro" "$@" "$image" >"$work/$name.log" 2>&1 || status=$?
	docker rm "$prefix-$name" >/dev/null
	check "$name ends with exit code $code" test "$status" -eq "$code"
	check "$name explains itself" grep -qF -- "$message" "$work/$name.log"
	check "$name keeps the passwords out of its log" no_secret_in "$work/$name.log"
}

no_secret_in() {
	! grep -qF --file <(cat "$work/secrets/master-password" "$work/secrets/db-password" "$work/wrong/master-password" \
		"$work/db/root-password") "$1"
}

# keytool of the JDK image, so the test needs no TLS tools on the host.
keytool() {
	docker run --rm --network host --volume "$work:/work" --entrypoint keytool "$helper" "$@" 2>/dev/null
}

# fetch_certificate: store the certificate of the server in cert.pem and print its SHA-256 fingerprint.
fetch_certificate() {
	keytool -printcert -sslserver "127.0.0.1:$port" -rfc | sed -n '/BEGIN CERT/,/END CERT/p' >"$work/cert.pem"
	keytool -printcert -file /work/cert.pem >"$work/cert.txt"
	sed -n 's/^[[:space:]]*SHA256: //p' "$work/cert.txt"
}

https() {
	curl --silent --show-error --cacert "$work/cert.pem" --resolve "$CN:$port:127.0.0.1" "$@"
}

status_of() {
	local path=$1
	shift
	https --output /dev/null --write-out '%{http_code}' "$@" "https://$CN:$port$path"
}

xmlrpc() {
	local method=$1 params=$2
	https --netrc-file "$work/netrc-good" --header 'Content-Type: text/xml' \
		--data "<?xml version=\"1.0\"?><methodCall><methodName>$method</methodName><params>$params</params></methodCall>" \
		"https://$CN:$port/xmlrpc/"
}

is_array() {
	grep -q '<array>' <<<"$1" && ! grep -q '<fault>' <<<"$1"
}

is_fault() {
	grep -q '<fault>' <<<"$1"
}

runs_as_nonroot() {
	local uids
	uids=$(docker top "$prefix-$1" -eo pid,uid | awk 'NR > 1 { print $2 }' | sort -u)
	[[ $uids == 65532 ]]
}

# in_volume VOLUME COMMAND: run a shell command as uid 65532 over a read-only copy of the volume.
in_volume() {
	docker run --rm --network none --user 65532:65532 --read-only --volume "$prefix-$1:/data:ro" \
		--volume "$work/secrets:/secrets:ro" --entrypoint bash "$helper" -c "$2"
}

docker network create "$network" >/dev/null

step "image $image"
docker image inspect --format 'user {{.Config.User}}, labels {{json .Config.Labels}}' "$image"
check "the image runs as uid 65532" test "$(docker image inspect --format '{{.Config.User}}' "$image")" = 65532:65532

step "first start with the embedded database"
start h2 h2
wait_ready h2
docker stats --no-stream --format 'memory after the start: {{.MemUsage}}' "$prefix-h2"
fingerprint=$(fetch_certificate)
grep -E '^(Owner|Issuer|Valid from):' "$work/cert.txt"
echo "SHA-256 $fingerprint"
check "the certificate names HIBISCUS_CERT_HOSTNAME" grep -qE "^Owner: .*CN=$CN(,|$)" "$work/cert.txt"
check "the certificate has a fingerprint" test -n "$fingerprint"
check "every process runs as uid 65532" runs_as_nonroot h2

step "authentication"
for path in "${PATHS[@]}"; do
	# The XML-RPC servlet answers GET with 405, so it gets a call.
	request=()
	if [[ $path == /xmlrpc/ ]]; then
		request=(--header 'Content-Type: text/xml' --data "$KONTO_FIND")
	fi
	check "$path without password answers 401" test "$(status_of "$path")" = 401
	check "$path with a wrong password answers 401" test "$(status_of "$path" --netrc-file "$work/netrc-bad")" = 401
	check "$path with the master password answers 200" test "$(status_of "$path" --netrc-file "$work/netrc-good" "${request[@]}")" = 200
done

step "XML-RPC"
answer=$(xmlrpc hibiscus.xmlrpc.konto.find '')
check "hibiscus.xmlrpc.konto.find returns an array" is_array "$answer"
answer=$(xmlrpc hibiscus.xmlrpc.address.find '<param><value><string></string></value></param>')
check "hibiscus.xmlrpc.address.find answers with a fault while address is not shared" is_fault "$answer"

step "user directory"
check "the user directory belongs to uid 65532 and is closed to others" test "$(in_volume h2 'stat -c "%a %u" /data')" = "700 65532"
check "every settings file of the entry point is readable for its owner only" test -z "$(in_volume h2 \
	'cd /data/cfg && find de.willuhn.jameica.hbci.rmi.HBCIDBService.properties de.willuhn.jameica.hbci.payment.Plugin.properties \
		de.willuhn.jameica.xmlrpc.Plugin.properties de.willuhn.jameica.webadmin.Plugin.properties \
		de.willuhn.jameica.services.UpdateService.properties de.willuhn.jameica.system.ApplicationCallback.properties \
		de.willuhn.jameica.system.Config.properties ! -perm 0600')"
check "the keystore exists" in_volume h2 'test -s /data/cfg/jameica.keystore'

step "restart on the same volume"
docker stop --time 60 "$prefix-h2" >/dev/null
code=$(docker inspect --format '{{.State.ExitCode}}' "$prefix-h2")
check "a stop ends the server cleanly (exit code $code)" test "$code" -eq 143 -o "$code" -eq 0
docker logs "$prefix-h2" >"$work/h2.log" 2>&1
check "the first start keeps the passwords out of its log" no_secret_in "$work/h2.log"
docker rm "$prefix-h2" >/dev/null
start h2-again h2 --env HIBISCUS_XMLRPC_SERVICES=konto,umsatz,address
wait_ready h2-again
check "the certificate stays the same" test "$(fetch_certificate)" = "$fingerprint"
answer=$(xmlrpc hibiscus.xmlrpc.address.find '<param><value><string></string></value></param>')
check "hibiscus.xmlrpc.address.find returns an array once address is shared" is_array "$answer"
docker stop --time 60 "$prefix-h2-again" >/dev/null
docker rm "$prefix-h2-again" >/dev/null

step "refusals"
status=0
timeout 180 docker run --name "$prefix-wrong" --network none "${hardening[@]}" --volume "$prefix-h2:/data" \
	--volume "$work/wrong:/run/secrets/hibiscus:ro" "$image" >"$work/wrong.log" 2>&1 || status=$?
docker rm "$prefix-wrong" >/dev/null
check "a wrong master password ends the start (exit code $status)" test "$status" -ne 0
check "the start with a wrong master password keeps the passwords out of its log" no_secret_in "$work/wrong.log"
refused missing-password 2 "HIBISCUS_MASTER_PASSWORD_FILE: /run/secrets/hibiscus/none is missing" \
	--env HIBISCUS_MASTER_PASSWORD_FILE=/run/secrets/hibiscus/none
refused short-interval 2 "HIBISCUS_SCHEDULER_INTERVAL_MINUTES=60 allows more than four fetches a day" \
	--env HIBISCUS_SCHEDULER_INTERVAL_MINUTES=60
refused unknown-service 2 "unknown XML-RPC service in HIBISCUS_XMLRPC_SERVICES: transfer" \
	--env HIBISCUS_XMLRPC_SERVICES=konto,transfer
refused plain-http 2 "HIBISCUS_HTTP_TLS=false sends the master password without encryption" \
	--env HIBISCUS_HTTP_TLS=false
docker volume rm "$prefix-h2" >/dev/null

step "MariaDB 11.8 with the character set utf8mb3"
docker run --detach --name "$prefix-db" --network "$network" --network-alias db --memory 512m \
	--volume "$work/db:/run/secrets/db:ro" \
	--env MARIADB_ROOT_PASSWORD_FILE=/run/secrets/db/root-password --env MARIADB_DATABASE=hibiscus \
	--env MARIADB_USER=hibiscus --env MARIADB_PASSWORD_FILE=/run/secrets/db/user-password \
	"$MARIADB_IMAGE" --character-set-server=utf8mb3 --collation-server=utf8mb3_general_ci >/dev/null
for _ in {1..60}; do
	if docker exec "$prefix-db" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; then
		break
	fi
	sleep 2
done
sql() {
	docker exec "$prefix-db" mariadb --defaults-extra-file=/run/secrets/db/client.cnf --batch --skip-column-names -e "$1"
}
sql "CREATE DATABASE hibiscus4 CHARACTER SET utf8mb4; GRANT ALL ON hibiscus4.* TO 'hibiscus'@'%';"

start maria maria --env HIBISCUS_DB_HOST=db
wait_ready maria
fetch_certificate >/dev/null
check "the vendor schema has 25 tables" \
	test "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'hibiscus'")" = 25
check "the schema version is set" test "$(sql "SELECT COUNT(*) FROM hibiscus.version WHERE name = 'db'")" = 1
answer=$(xmlrpc hibiscus.xmlrpc.konto.find '')
check "hibiscus.xmlrpc.konto.find returns an array with MariaDB" is_array "$answer"
check "the settings no longer hold the database password in clear text" in_volume maria \
	'! grep -qF --file /secrets/db-password /data/cfg/de.willuhn.jameica.hbci.rmi.HBCIDBService.properties'
docker stop --time 60 "$prefix-maria" >/dev/null
docker logs "$prefix-maria" >"$work/maria.log" 2>&1
check "the start with MariaDB keeps the passwords out of its log" no_secret_in "$work/maria.log"
docker rm "$prefix-maria" >/dev/null

status=0
timeout 180 docker run --name "$prefix-utf8mb4" --network "$network" "${hardening[@]}" \
	--volume "$work/secrets:/run/secrets/hibiscus:ro" --env HIBISCUS_DB_HOST=db --env HIBISCUS_DB_NAME=hibiscus4 \
	"$image" >"$work/utf8mb4.log" 2>&1 || status=$?
docker rm "$prefix-utf8mb4" >/dev/null
check "a utf8mb4 database is refused (exit code $status)" test "$status" -eq 2
check "the refusal names the character set" grep -qF "refusing to create the schema in a utf8mb4 database" "$work/utf8mb4.log"
check "the utf8mb4 database stays empty" \
	test "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'hibiscus4'")" = 0

if [[ ${#failures[@]} -gt 0 ]]; then
	printf '\nimage test failed in %d checks:\n' "${#failures[@]}" >&2
	printf '  - %s\n' "${failures[@]}" >&2
	exit 1
fi
printf '\nimage test passed\n'
