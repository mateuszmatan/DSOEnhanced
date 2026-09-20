#!/usr/bin/env bash
# Checks every call made on a field, local variable, parameter or class of the
# library: the method must exist and accept the number of arguments passed.
# Groovy resolves such calls dynamically, so groovyc stays silent while the
# Jenkins sandbox rejects them at runtime with an invokeMethod signature.
cd "$(dirname "$0")/../.." || exit 1
python3 - <<'PY' || exit 1
import re, glob, sys

src_files = sorted(glob.glob('src/**/*.groovy', recursive=True))
var_files = sorted(glob.glob('vars/*.groovy'))

OBJECT_METHODS = set('''
toString hashCode equals getClass clone wait notify notifyAll finalize invokeMethod getProperty setProperty
each eachWithIndex collect find findAll findResult any every inject with is asType asBoolean getAt putAt
sort unique reverse size isEmpty contains join tokenize split trim class metaClass properties respondsTo hasProperty
'''.split())

DECL_WITH_BODY = (r'^\s*(?:@\w+\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*'
                  r'(?:[\w.<>\[\],]+\s+)?(\w+)\s*\(([^)]*)\)\s*\{')
DECL_ABSTRACT = (r'^\s*(?:@\w+\s+)*(?:(?:public|protected|abstract)\s+)*'
                 r'(?:[\w.<>\[\],]+\s+)(\w+)\s*\(([^)]*)\)\s*$')

def param_range(sig):
    sig = sig.strip()
    if not sig:
        return (0, 0)
    depth, parts, current = 0, [], ''
    for ch in sig:
        if ch in '([{<':
            depth += 1
        elif ch in ')]}>':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += ch
    parts.append(current)
    defaults = sum(1 for p in parts if '=' in p)
    return (len(parts) - defaults, len(parts))

classes, signatures = {}, {}
for f in src_files:
    text = open(f).read()
    m = re.search(r'^\s*(?:abstract\s+)?(?:class|interface|trait)\s+(\w+)(?:\s+extends\s+(\w+))?', text, re.M)
    if not m:
        continue
    name, parent = m.group(1), m.group(2)
    methods = set()
    for pattern in (DECL_WITH_BODY, DECL_ABSTRACT):
        for dm in re.finditer(pattern, text, re.M):
            methods.add(dm.group(1))
            signatures.setdefault((name, dm.group(1)), []).append(param_range(dm.group(2)))
    fields = set(re.findall(
        r'^\s*(?:@\w+\s+)*(?:(?:public|private|protected|static|final)\s+)*'
        r'(?:def|Map|List|Set|String|int|long|boolean|double|[A-Z]\w*)\s+(\w+)\s*(?:=|$)', text, re.M))
    for fld in fields:
        cap = fld[0].upper() + fld[1:]
        methods.update({'get' + cap, 'set' + cap, 'is' + cap})
    classes[name] = {'methods': methods, 'parent': parent}

def resolves(cls, method):
    seen = set()
    while cls and cls in classes and cls not in seen:
        seen.add(cls)
        if method in classes[cls]['methods']:
            return True
        cls = classes[cls]['parent']
    return False

def signature_of(cls, method):
    seen = set()
    while cls and cls in classes and cls not in seen:
        seen.add(cls)
        if (cls, method) in signatures:
            return signatures[(cls, method)]
        cls = classes[cls]['parent']
    return None

def strip_strings(text):
    out, i, n = [], 0, len(text)
    while i < n:
        ch = text[i]
        if ch in '"\'':
            quote = ch
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            out.append('S')
        else:
            out.append(ch)
            i += 1
    return ''.join(out)

def arg_count(args):
    args = strip_strings(args).strip()
    if not args:
        return 0
    if re.match(r'^\w+\s*:', args):
        return 1
    depth, count = 0, 1
    for ch in args:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            count += 1
    return count

def receivers_of(text):
    receivers = {}
    for rm in re.finditer(r'^\s*(?:@\w+\s+)*(?:(?:public|private|protected|static|final)\s+)*([A-Z]\w*)\s+(\w+)\s*(?:=|$)', text, re.M):
        if rm.group(1) in classes:
            receivers[rm.group(2)] = rm.group(1)
    for rm in re.finditer(r'\b([A-Z]\w*)\s+(\w+)\s*=\s*new\s+\1\b', text):
        if rm.group(1) in classes:
            receivers[rm.group(2)] = rm.group(1)
    for rm in re.finditer(r'\(([^)]*)\)\s*\{', text):
        for part in rm.group(1).split(','):
            bits = part.strip().split()
            if len(bits) == 2 and bits[0] in classes:
                receivers[bits[1]] = bits[0]
    for cls in classes:
        receivers[cls] = cls
    return receivers

problems = []
for f in src_files + var_files:
    text = open(f).read()
    for name, cls in receivers_of(text).items():
        for cm in re.finditer(r'\b' + re.escape(name) + r'\.(\w+)\s*\(', text):
            method = cm.group(1)
            if method in OBJECT_METHODS:
                continue
            line = text[:cm.start()].count('\n') + 1
            if not resolves(cls, method):
                problems.append("%s:%d calls %s.%s() but %s declares no such method" % (f, line, name, method, cls))
                continue
            sig = signature_of(cls, method)
            closing = re.match(r'\(([^()]*)\)', text[cm.end() - 1:])
            if not sig or not closing:
                continue
            count = arg_count(closing.group(1))
            if re.match(r'\s*\{', text[cm.end() - 1 + closing.end():cm.end() + closing.end() + 40]):
                count += 1
            if not any(lo <= count <= hi for lo, hi in sig):
                allowed = ', '.join('%d-%d' % r for r in sig)
                problems.append("%s:%d calls %s.%s() with %d argument(s) but it accepts %s" % (f, line, name, method, count, allowed))

if problems:
    print("API CONSISTENCY CHECK FAILED - the sandbox rejects these calls at runtime:")
    for p in sorted(set(problems)):
        print("  " + p)
    sys.exit(1)
print("API CONSISTENCY CHECK PASSED - every call on a library type resolves with a matching argument count")
PY
