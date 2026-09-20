#!/usr/bin/env bash
# Fails when a class calls a method on itself that is declared nowhere in src.
# Groovy compiles such a call dynamically, so groovyc stays silent, while the
# Jenkins sandbox rejects it at runtime with
# "Scripts not permitted to use method groovy.lang.GroovyObject invokeMethod".
cd "$(dirname "$0")/../.." || exit 1
python3 - <<'PY' || exit 1
import re, glob, sys

declared = set()
files = sorted(glob.glob('src/**/*.groovy', recursive=True))
decl_re = re.compile(
    r'^\s*(?:@\w+\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*'
    r'(?:[\w.<>\[\],\s]+\s+)?(\w+)\s*\([^)]*\)\s*\{', re.M)
for f in files:
    for m in decl_re.finditer(open(f).read()):
        declared.add(m.group(1))

builtins = set('''
if for while switch catch return new this super it echo sh bat powershell error sleep timeout parallel node stage script
readYaml readJSON writeJSON readFile writeFile fileExists findFiles libraryResource withCredentials withEnv string
usernamePassword unstable junit archiveArtifacts publishHTML cleanWs build copyArtifacts lastSuccessful tool dir
println print printf assert each eachWithIndex collect collectEntries find findAll findResult any every inject
split join trim tokenize replace replaceAll replaceFirst startsWith endsWith contains matches toString toInteger
size length substring toLowerCase toUpperCase add addAll put putAll get getAt remove removeAll containsKey keySet
values entrySet isEmpty sort unique reverse first last take drop subList append toList asBoolean equals hashCode
getClass getSimpleName getMessage min max abs round intdiv plus minus multiply div mod power leftShift
sprintf format valueOf parseInt parseDouble parseLong number boolean String Integer Double Long Boolean Map List Set
Expando Date SimpleDateFormat StringBuilder HashMap Exception Throwable RuntimeException
currentTimeMillis nanoTime getProperty setProperty invokeMethod
'''.split())

call_re = re.compile(r'(?<![\w.$])([a-z]\w*)\s*\(')
problems = []
for f in files:
    src = open(f).read()
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r"'''(?:.|\n)*?'''", "''", src)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    src = re.sub(r"'(?:[^'\\\n]|\\.)*'", "''", src)
    locals_ = set(re.findall(r'\b(?:def|Map|List|String|int|boolean|double|long|Closure)\s+(\w+)\s*=', src))
    params = set()
    for m in re.finditer(r'\(([^)]*)\)\s*\{', src):
        for part in m.group(1).split(','):
            bits = part.strip().split()
            if bits:
                params.add(bits[-1])
    for m in call_re.finditer(src):
        name = m.group(1)
        if name in declared or name in builtins or name in locals_ or name in params or name == 'def':
            continue
        problems.append("%s:%d calls %s() which is declared nowhere in src" % (f, src[:m.start()].count('\n') + 1, name))

if problems:
    print("UNDEFINED CALL SCAN FAILED - the Jenkins sandbox rejects these at runtime:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("UNDEFINED CALL SCAN PASSED - every implicit call resolves to a declared method")
PY
