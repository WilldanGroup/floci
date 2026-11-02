#!/usr/bin/env bash
# Unit tests for healthcheck.sh's port.
# Run directly: bash docker/test-healthcheck.sh
# Exit 0 on success, non-zero on first failure summary.
#
# Each case runs the probe against a listener on the host that answers /_floci/init the way a ready
# Floci does, on the port the case names. The listener is python3's, which every CI runner has.

set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/healthcheck.sh"
PASS=0
FAIL=0

assert_eq() {
    desc="$1"; expected="$2"; actual="$3"
    if [ "${actual}" = "${expected}" ]; then
        printf '[PASS] %s\n' "${desc}"
        PASS=$((PASS + 1))
    else
        printf '[FAIL] %s\n  expected: %s\n  actual:   %s\n' "${desc}" "${expected}" "${actual}"
        FAIL=$((FAIL + 1))
    fi
}

if ! command -v python3 >/dev/null 2>&1; then
    printf '[SKIP] every case (python3 not installed)\n'
    exit 0
fi

WORK="$(mktemp -d)"
LISTENER=
stop_listener() {
    if [ -n "${LISTENER}" ]; then
        kill "${LISTENER}" 2>/dev/null || true
        wait "${LISTENER}" 2>/dev/null || true
        LISTENER=
    fi
}
trap 'stop_listener; rm -rf "${WORK}"' EXIT INT TERM

cat > "${WORK}/listener.py" <<'EOF'
import http.server, sys

class Init(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        ready = self.path == "/_floci/init"
        body = b'{"completed":{"boot":true,"start":true,"ready":true,"shutdown":false},"scripts":{}}' if ready else b""
        self.send_response(200 if ready else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

server = http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Init)
print(server.server_address[1], flush=True)
server.serve_forever()
EOF

# Starts the listener on the given port (0 for any free one) and sets PORT to the port it took, or to
# nothing when the port could not be bound.
start_listener() {
    PORT=
    : > "${WORK}/port"
    python3 "${WORK}/listener.py" "$1" > "${WORK}/port" 2>"${WORK}/listener.err" &
    LISTENER=$!
    for _ in $(seq 1 50); do
        if [ -s "${WORK}/port" ]; then
            PORT="$(cat "${WORK}/port")"
            return
        fi
        kill -0 "${LISTENER}" 2>/dev/null || break
        sleep 0.1
    done
    stop_listener
}

# Runs the probe with the given environment and prints how it exited.
probe() {
    if env "$@" bash "${SCRIPT}" >/dev/null 2>&1; then
        printf 'healthy\n'
    else
        printf 'unhealthy\n'
    fi
}

# --- the probe opens the port FLOCI_PORT names ---
start_listener 0
if [ -z "${PORT}" ]; then
    printf '[FAIL] a listener starts on a free port\n  stderr was:\n%s\n' "$(cat "${WORK}/listener.err")"
    FAIL=$((FAIL + 1))
else
    assert_eq "FLOCI_PORT=${PORT} probes port ${PORT}" \
        "healthy" \
        "$(probe FLOCI_PORT="${PORT}")"
fi
stop_listener

# --- with no FLOCI_PORT the probe opens 4566 ---
start_listener 4566
if [ -z "${PORT}" ]; then
    printf '[SKIP] no FLOCI_PORT probes port 4566 (4566 is in use on this host)\n'
else
    assert_eq "no FLOCI_PORT probes port 4566" \
        "healthy" \
        "$(probe -u FLOCI_PORT)"
fi
stop_listener

printf '\n%d passed, %d failed\n' "${PASS}" "${FAIL}"
[ "${FAIL}" -eq 0 ]
