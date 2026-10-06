#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
failures=0
check() {
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1"; echo "      expected [$3], got [$2]"; failures=$((failures + 1)); fi
}

python3 - "$ROOT/src/com/bbh/config/PortalConfigReader.groovy" "$WORK/query.sh" <<'PY'
import re, sys
source = open(sys.argv[1]).read()
script = re.search(r"String queryScript\(\) \{\s*return '''(.*?)'''", source, re.S).group(1)
open(sys.argv[2], 'w').write(script.replace('\\\\', '\\'))
PY

cat > "$WORK/server.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
OK = '11111111-1111-4111-8111-111111111111'
REVOKED = '22222222-2222-4222-8222-222222222222'
FLAKY = '33333333-3333-4333-8333-333333333333'
hits = {}
class Portal(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def answer(self, status, body, kind='application/json'):
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def problem(self, status, title, detail, key):
        self.answer(status, json.dumps({'type': 'about:blank', 'title': title, 'status': status, 'detail': detail,
                                        'instance': '/api/dso/config/' + key}), 'application/problem+json')
    def do_GET(self):
        path, _, query = self.path.partition('?')
        key = path.rsplit('/', 1)[-1]
        hits[key] = hits.get(key, 0) + 1
        if not path.startswith('/portal/api/dso/config/') or query != 'format=json' or self.headers.get('Accept') != 'application/json':
            self.answer(404, '{"status":404,"error":"Not Found"}')
        elif key == OK:
            self.answer(200, json.dumps({'pipeline': {'type': 'full', 'product': 'CERTSCANNER'}, 'projects': {'gui': {'appId': 'café'}}}, ensure_ascii=False))
        elif key == REVOKED:
            self.problem(403, 'Pipeline key invalidated', 'The DevSecOps pipeline key was invalidated on 2026-10-06T08:14:19Z: moved', key)
        elif key == FLAKY and hits[key] < 3:
            self.answer(503, '<html>Application is not available</html>', 'text/html')
        elif key == FLAKY:
            self.answer(200, '{"projects":{"backend-api":{}}}')
        else:
            self.problem(404, 'Not found', 'Unknown DevSecOps pipeline key', key)
server = HTTPServer(('127.0.0.1', 0), Portal)
print(server.server_port, flush=True)
server.serve_forever()
PY
python3 "$WORK/server.py" > "$WORK/port" &
SERVER=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$WORK/port" ] && break; sleep 0.2; done
PORT="$(cat "$WORK/port")"

OK=11111111-1111-4111-8111-111111111111
REVOKED=22222222-2222-4222-8222-222222222222
FLAKY=33333333-3333-4333-8333-333333333333
UNKNOWN=44444444-4444-4444-8444-444444444444

DIR="$WORK/run"
DSO_PORTAL_DIR="$DIR" DSO_PORTAL_URL="http://127.0.0.1:$PORT/portal" DSO_PORTAL_KEYS="$OK $REVOKED $UNKNOWN $FLAKY" sh "$WORK/query.sh"
check 'the script ends with status 0' "$?" 0
check 'one answer per key, in key order' "$(ls "$DIR" | tr '\n' ' ')" 'body-0 body-1 body-2 body-3 error-0 error-1 error-2 error-3 status-0 status-1 status-2 status-3 '
check 'the run directory and its files are private to the agent user' "$(stat -c '%a' "$DIR" "$DIR/body-0" | tr '\n' ' ')" '700 600 '
SUM="$(sha256sum "$DIR/body-0" | cut -c1-16)"
check 'an active key: HTTP 200 and the first 16 hex digits of the sha256 of the body' "$(cat "$DIR/status-0")" "200 $SUM"
check 'an active key: the body is the configuration as the portal sent it, UTF-8 included' "$(cat "$DIR/body-0")" '{"pipeline": {"type": "full", "product": "CERTSCANNER"}, "projects": {"gui": {"appId": "café"}}}'
check 'an invalidated key: HTTP 403 with the problem detail of the portal' "$(cut -d' ' -f1 "$DIR/status-1") $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["detail"])' "$DIR/body-1")" \
  '403 The DevSecOps pipeline key was invalidated on 2026-10-06T08:14:19Z: moved'
check 'an unknown key: HTTP 404 with the problem detail of the portal' "$(cut -d' ' -f1 "$DIR/status-2") $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["detail"])' "$DIR/body-2")" \
  '404 Unknown DevSecOps pipeline key'
check 'a portal that answers 503 twice is asked again and the third answer counts' "$(cut -d' ' -f1 "$DIR/status-3") $(cat "$DIR/body-3")" '200 {"projects":{"backend-api":{}}}'
check 'curl wrote no error for answered requests' "$(cat "$DIR"/error-* | wc -c | tr -d ' ')" 0

DIR="$WORK/down"
CLOSED="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
DSO_PORTAL_DIR="$DIR" DSO_PORTAL_URL="http://127.0.0.1:$CLOSED" DSO_PORTAL_KEYS="$OK" sh "$WORK/query.sh"
check 'an unreachable portal: status 000, the script still ends with status 0' "$? $(cut -d' ' -f1 "$DIR/status-0")" '0 000'
check 'an unreachable portal: one curl error naming the cause, not the key' "$(grep -c '^curl: (7) Failed to connect' "$DIR/error-0") $(grep -c "$OK" "$DIR/error-0")" '1 0'

echo
[ "$failures" -eq 0 ] && echo 'ALL QUERY SCRIPT SCENARIOS PASSED' || echo "$failures QUERY SCRIPT SCENARIO CHECK(S) FAILED"
[ "$failures" -eq 0 ]
