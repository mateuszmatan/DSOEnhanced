#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
failures=0
check() {
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1"; echo "      expected [$3], got [$2]"; failures=$((failures + 1)); fi
}

python3 - "$ROOT/src/com/bbh/core/OneViewPost.groovy" "$ROOT/src/com/bbh/remediation/OneViewDecorationReporter.groovy" "$WORK" <<'PY'
import re, sys
source = open(sys.argv[1]).read()
reporter = open(sys.argv[2]).read()
work = sys.argv[3]
path = re.search(r"String path = '([^']*)'", reporter).group(1)
for name, method in (('upload.sh', 'unixScript'), ('upload.ps1', 'windowsScript')):
    body = re.search(r"static String " + method + r"\(String path\) \{\s*String template = '''(.*?)'''\s*return template\.replace\('/api/dso/reports', path\)", source, re.S).group(1)
    body = body.replace('\\\\', '\\')
    open(work + '/' + name, 'w').write(body)
    open(work + '/decorate.' + name.split('.')[1], 'w').write(body.replace('/api/dso/reports', path))
open(work + '/path', 'w').write(path)
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
        if self.path == '/oneview/api/dso/bitbucket/pull-requests/decorate' and self.headers.get('Content-Type') == 'application/json':
            decoration = json.loads(text)
            with open(received + '.decorations', 'a') as out:
                out.write(json.dumps(decoration, sort_keys=True) + '\n')
            if decoration.get('job') == 'broken':
                self.answer(500, json.dumps({'type': 'about:blank', 'title': 'Request failed', 'status': 500,
                                             'detail': "The portal could not handle the request. The failure is in the portal's log.",
                                             'instance': '/api/dso/bitbucket/pull-requests/decorate'}, separators=(',', ':')),
                            'application/problem+json')
            else:
                self.answer(200, json.dumps({'pullRequestId': decoration.get('pullRequestId'), 'commentsAdded': decoration.get('generalComments') or 0,
                                             'lineCommentsAdded': decoration.get('lineComments') or 0, 'status': 'RECORDED',
                                             'message': 'Recorded the decoration of pull request ' + decoration.get('pullRequestId') + ' for pipeline 3'},
                                            separators=(',', ':')))
            return
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

decorate() {
  printf '{"service":"gui","kind":"BRANCH","pullRequestId":"77","lineComments":3,"findings":[{"tool":"Nexus IQ","line":null}],"pipelineKey":"%s","job":"%s","build":42}' "$KEY" "$1" > "$WORK/decoration.json"
  ( cd "$WORK" && ONEVIEW_URL="$2" ONEVIEW_FILE=decoration.json ONEVIEW_RESPONSE=response.json sh "$WORK/decorate.sh" > "$WORK/out" 2>&1 )
}

check 'the scripts of the pull request decorations are the upload scripts with the path that OneViewDecorationReporter gives them' \
  "$(cat "$WORK/path") $(cat "$WORK/decorate.sh" "$WORK/decorate.ps1" | grep -c '/api/dso/bitbucket/pull-requests/decorate') $(cat "$WORK/decorate.sh" "$WORK/decorate.ps1" | grep -c '/api/dso/reports')" \
  '/api/dso/bitbucket/pull-requests/decorate 2 0'
decorate 'CertScanner-build' "http://127.0.0.1:$PORT/oneview"
check 'a stored decoration: the script ends with status 0 and OneView received the decoration with the pipeline key, a finding without a line included' \
  "$? $(python3 -c 'import json,sys; d=json.loads(open(sys.argv[1]).readline()); print(d["pipelineKey"], d["kind"], d["pullRequestId"], d["findings"][0]["line"])' "$WORK/received.decorations")" "0 $KEY BRANCH 77 None"
check 'a stored decoration: the console shows the status and the answer of OneView, which counts the comments and holds no pipeline key, and no part of the output holds the key' \
  "$(cat "$WORK/out") $(grep -c "$KEY" "$WORK/out")" \
  '[ONEVIEW] HTTP 200 {"pullRequestId":"77","commentsAdded":0,"lineCommentsAdded":3,"status":"RECORDED","message":"Recorded the decoration of pull request 77 for pipeline 3"} 0'
decorate 'broken' "http://127.0.0.1:$PORT/oneview"
check 'OneView answers 500 to a decoration: the script ends with status 1 after the line with the status and the problem' "$? $(cat "$WORK/out")" \
  '1 [ONEVIEW] HTTP 500 {"type":"about:blank","title":"Request failed","status":500,"detail":"The portal could not handle the request. The failure is in the portal'\''s log.","instance":"/api/dso/bitbucket/pull-requests/decorate"}'
check 'the Windows script of the decorations posts to the path of the decorations with the same time limits' \
  "$(grep -c -- '--connect-timeout 10 --max-time 60 .*-X POST "\$env:ONEVIEW_URL/api/dso/bitbucket/pull-requests/decorate"' "$WORK/decorate.ps1")" '1'

echo
[ "$failures" -eq 0 ] && echo 'ALL UPLOAD SCRIPT SCENARIOS PASSED' || echo "$failures UPLOAD SCRIPT SCENARIO CHECK(S) FAILED"
[ "$failures" -eq 0 ]
