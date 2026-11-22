#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
failures=0
check() {
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1"; echo "      expected [$3], got [$2]"; failures=$((failures + 1)); fi
}

python3 - "$ROOT/src/com/bbh/report/OneViewReportService.groovy" "$WORK/upload.sh" "$WORK/upload.ps1" <<'PY'
import re, sys
source = open(sys.argv[1]).read()
shell = re.search(r"script\.sh '''(.*?)'''", source, re.S).group(1)
power = re.search(r"script\.powershell '''(.*?)'''", source, re.S).group(1)
open(sys.argv[2], 'w').write(shell.replace('\\\\', '\\'))
open(sys.argv[3], 'w').write(power.replace('\\\\', '\\'))
PY

cat > "$WORK/server.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
received = sys.argv[1]
class OneView(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def answer(self, status, body, kind='application/json'):
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_POST(self):
        text = self.rfile.read(int(self.headers.get('Content-Length') or 0)).decode('utf-8')
        if self.path != '/oneview/api/dso/reports' or self.headers.get('Content-Type') != 'application/json':
            self.answer(404, '{"status":404,"error":"Not Found"}')
            return
        report = json.loads(text)
        with open(received, 'a') as out:
            out.write(json.dumps(report, sort_keys=True) + '\n')
        key = report.get('pipelineKey')
        if report.get('job') == 'broken':
            self.answer(500, json.dumps({'type': 'about:blank', 'title': 'Internal Server Error', 'status': 500,
                                         'detail': 'OneView could not handle the request.', 'instance': '/api/dso/reports'}),
                        'application/problem+json')
        elif report.get('job') == 'spaced':
            self.answer(201, '{"id": 8, "pipelineId": 3, "pipelineKey" : "' + key + '", "jobName": "spaced"}')
        else:
            self.answer(201, json.dumps({'id': 7, 'pipelineId': 3, 'pipelineKey': key, 'jobName': report.get('job'),
                                         'buildNumber': report.get('build'), 'result': report.get('result')}, separators=(',', ':')))
server = HTTPServer(('127.0.0.1', 0), OneView)
print(server.server_port, flush=True)
server.serve_forever()
PY
python3 "$WORK/server.py" "$WORK/received" > "$WORK/port" &
SERVER=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$WORK/port" ] && break; sleep 0.2; done
PORT="$(cat "$WORK/port")"
CLOSED="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
KEY=5e0f0001-aaaa-4bbb-8ccc-1234567890ab

post() {
  printf '{"pipelineKey":"%s","job":"%s","build":42,"result":"SUCCESS","reportHtml":"<html></html>"}' "$KEY" "$1" > "$WORK/report.json"
  ( cd "$WORK" && ONEVIEW_URL="$2" ONEVIEW_FILE=report.json ONEVIEW_RESPONSE=response.json sh "$WORK/upload.sh" > "$WORK/out" 2>&1 )
}

post 'CertScanner-build' "http://127.0.0.1:$PORT/oneview"
check 'a stored report: the script ends with status 0' "$?" 0
check 'a stored report: OneView received the report with the pipeline key' \
  "$(python3 -c 'import json,sys; r=json.loads(open(sys.argv[1]).readline()); print(r["pipelineKey"], r["job"], r["build"])' "$WORK/received")" "$KEY CertScanner-build 42"
check 'a stored report: the console shows the status and the answer of OneView without the pipeline key' "$(cat "$WORK/out")" \
  '[ONEVIEW] HTTP 201 {"id":7,"pipelineId":3,"pipelineKey":"(not shown)","jobName":"CertScanner-build","buildNumber":42,"result":"SUCCESS"}'
check 'a stored report: no part of the output holds the pipeline key' "$(grep -c "$KEY" "$WORK/out")" 0

post 'spaced' "http://127.0.0.1:$PORT/oneview"
check 'an answer written with spaces around the colon: the pipeline key is not shown either' "$? $(cat "$WORK/out")" \
  '0 [ONEVIEW] HTTP 201 {"id": 8, "pipelineId": 3, "pipelineKey":"(not shown)", "jobName": "spaced"}'

post 'broken' "http://127.0.0.1:$PORT/oneview"
check 'OneView answers 500: the script ends with status 1 after the line with the status and the problem' "$? $(cat "$WORK/out")" \
  '1 [ONEVIEW] HTTP 500 {"type": "about:blank", "title": "Internal Server Error", "status": 500, "detail": "OneView could not handle the request.", "instance": "/api/dso/reports"}'

post 'CertScanner-build' "http://127.0.0.1:$CLOSED"
check 'an unreachable OneView: the status of curl, one curl error naming the cause, and no pipeline key' \
  "$? $(grep -c '^curl: (7) Failed to connect' "$WORK/out") $(grep -c 'ONEVIEW. HTTP' "$WORK/out") $(grep -c "$KEY" "$WORK/out")" '7 1 0 0'

check 'both scripts give up after 10 seconds without a connection and after 60 seconds in all' \
  "$(grep -c -- '--connect-timeout 10 --max-time 60' "$WORK/upload.sh") $(grep -c -- '--connect-timeout 10 --max-time 60' "$WORK/upload.ps1")" '1 1'
check 'the Windows script replaces the pipeline key of the answer before it prints it, and neither script names a former variable' \
  "$(grep -c -- "-replace '\"pipelineKey\" \\*: \\*\"\\[^\"\\]\\*\"', '\"pipelineKey\":\"(not shown)\"'" "$WORK/upload.ps1") $(grep -c 'Get-Content -Raw \$env:ONEVIEW_RESPONSE -ErrorAction SilentlyContinue)\"' "$WORK/upload.ps1") $(cat "$WORK/upload.sh" "$WORK/upload.ps1" | grep -c 'PORTAL')" '1 0 0'

echo
[ "$failures" -eq 0 ] && echo 'ALL UPLOAD SCRIPT SCENARIOS PASSED' || echo "$failures UPLOAD SCRIPT SCENARIO CHECK(S) FAILED"
[ "$failures" -eq 0 ]
