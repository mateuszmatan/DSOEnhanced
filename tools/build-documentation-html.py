#!/usr/bin/env python3
"""Render documentation.confluence into documentation.html.

documentation.confluence is the single source of the library documentation. It is
written in Confluence wiki markup so that it can be pasted into Confluence without
conversion; this script renders the same content as a standalone HTML page.
"""
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'documentation.confluence'
DEFAULT_TARGET = ROOT / 'documentation.html'

PANELS = {'info': 'callout info', 'note': 'callout', 'warning': 'callout stop', 'tip': 'callout ok'}

KEYWORDS = ('projects', 'def', 'if', 'else', 'return', 'import', 'class', 'void', 'String', 'Map', 'List')


def colour(code, language):
    if language in ('yaml', 'none', ''):
        code = re.sub(r'(?m)(^\s*)([\w.-]+)(:)', r'\1<span class="k">\2</span>\3', code)
        code = re.sub(r'(?m)(#.*)$', r'<span class="c">\1</span>', code)
        code = re.sub(r'(&quot;[^&]*?&quot;|&#x27;[^&]*?&#x27;)', r'<span class="s">\1</span>', code)
    elif language in ('groovy', 'java'):
        code = re.sub(r'\b(%s)\b' % '|'.join(KEYWORDS), r'<span class="kw">\1</span>', code)
        code = re.sub(r'(&#x27;[^&]*?&#x27;)', r'<span class="s">\1</span>', code)
        code = re.sub(r'(?m)(//.*)$', r'<span class="c">\1</span>', code)
    elif language == 'bash':
        code = re.sub(r'(?m)(#.*)$', r'<span class="c">\1</span>', code)
    return code


def inline(text):
    text = html.escape(text, quote=False)
    holds = []

    def hold(markup):
        holds.append(markup)
        return '\x00%d\x00' % (len(holds) - 1)

    text = re.sub(r'\{\{(.+?)\}\}', lambda m: hold('<code>%s</code>' % m.group(1)), text)
    text = re.sub(r'\[([^\]|]+)\|(#[\w-]+)\]', lambda m: hold('<a href="%s">%s</a>' % (m.group(2), m.group(1))), text)
    text = re.sub(r'\[([^\]|]+)\|(https?://[^\]]+)\]',
                  lambda m: hold('<a href="%s" target="_blank" rel="noreferrer">%s</a>' % (m.group(2), m.group(1))), text)
    text = re.sub(r'(?<![\w*])\*([^*\n]+)\*(?![\w*])', lambda m: hold('<strong>%s</strong>' % m.group(1)), text)
    text = re.sub(r'(?<![\w_])_([^_\n]+)_(?![\w_])', lambda m: hold('<em>%s</em>' % m.group(1)), text)
    for index, markup in enumerate(holds):
        text = text.replace('\x00%d\x00' % index, markup)
    return text


def cell(text):
    return inline(text.replace('\\|', '\x01')).replace('\x01', '|').strip()


def render(lines):
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]

        match = re.match(r'^\{code(?::(\w+))?\}\s*$', line)
        if match:
            language = match.group(1) or 'none'
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith('{code}'):
                body.append(lines[i])
                i += 1
            i += 1
            escaped = html.escape('\n'.join(body))
            out.append('<pre><code>%s</code></pre>' % colour(escaped, language))
            continue

        match = re.match(r'^\{(info|note|warning|tip)\}\s*$', line)
        if match:
            name = match.group(1)
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith('{%s}' % name):
                body.append(lines[i])
                i += 1
            i += 1
            out.append('<div class="%s">%s</div>' % (PANELS[name], '<br>'.join(inline(b) for b in body if b.strip())))
            continue

        match = re.match(r'^\{anchor:([\w-]+)\}\s*$', line)
        if match:
            out.append('<a id="%s"></a>' % match.group(1))
            i += 1
            continue

        match = re.match(r'^h([1-5])\.\s*(.+)$', line)
        if match:
            level = int(match.group(1))
            tag = 'h2' if level == 1 else ('h3' if level == 2 else 'h4')
            out.append('<%s>%s</%s>' % (tag, inline(match.group(2).strip()), tag))
            i += 1
            continue

        if line.startswith('||'):
            head = [c for c in re.split(r'\|\|', line.strip())[1:-1]]
            rows = []
            i += 1
            while i < len(lines) and lines[i].startswith('|') and not lines[i].startswith('||'):
                rows.append([c for c in re.split(r'(?<!\\)\|', lines[i].strip())[1:-1]])
                i += 1
            thead = ''.join('<th>%s</th>' % cell(c) for c in head)
            tbody = ''.join('<tr>%s</tr>' % ''.join('<td>%s</td>' % cell(c) for c in r) for r in rows)
            out.append('<table><thead><tr>%s</tr></thead><tbody>%s</tbody></table>' % (thead, tbody))
            continue

        if re.match(r'^----+\s*$', line):
            out.append('<hr>')
            i += 1
            continue

        match = re.match(r'^([*#]+)\s+(.*)$', line)
        if match:
            items = []
            while i < len(lines):
                nested = re.match(r'^([*#]+)\s+(.*)$', lines[i])
                if not nested:
                    break
                items.append((len(nested.group(1)), nested.group(1)[-1], nested.group(2)))
                i += 1
            out.append(render_list(items))
            continue

        if line.strip():
            block = [line]
            i += 1
            stop = r'^(h[1-5]\.|\|\||\{code|\{info|\{note|\{warning|\{tip|\{anchor|----|[*#]+\s)'
            while i < len(lines) and lines[i].strip() and not re.match(stop, lines[i]):
                block.append(lines[i])
                i += 1
            out.append('<p>%s</p>' % inline(' '.join(block)))
            continue
        i += 1
    return '\n'.join(out)


def render_list(items, index=0, depth=1):
    tag = 'ol' if items[index][1] == '#' else 'ul'
    parts = ['<%s>' % tag]
    while index < len(items):
        level, _, text = items[index]
        if level < depth:
            break
        if level > depth:
            nested, index = render_list(items, index, depth + 1)
            parts[-1] = parts[-1][:-len('</li>')] + nested + '</li>'
            continue
        parts.append('<li>%s</li>' % inline(text))
        index += 1
    parts.append('</%s>' % tag)
    return (''.join(parts), index) if depth > 1 else ''.join(parts)


def main():
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_TARGET
    lines = SOURCE.read_text().split('\n')

    title = re.sub(r'^h1\.\s*', '', lines[0]).strip()
    chapters = []
    current = {'anchor': '', 'number': '', 'title': 'Contents', 'lines': []}
    for line in lines[1:]:
        anchor = re.match(r'^\{anchor:(ch\d+)\}\s*$', line)
        if anchor:
            chapters.append(current)
            current = {'anchor': anchor.group(1), 'number': '', 'title': '', 'lines': []}
            continue
        heading = re.match(r'^h1\.\s*(\d+)\.\s*(.+)$', line)
        if heading and current['anchor'] and not current['title']:
            current['number'], current['title'] = heading.group(1), heading.group(2).strip()
            continue
        current['lines'].append(line)
    chapters.append(current)

    sections = []
    for chapter in chapters:
        body = render(chapter['lines'])
        if not body.strip():
            continue
        css = 'section toc' if chapter['title'] == 'Contents' else 'section'
        badge = '<span class="num">%s</span> ' % chapter['number'] if chapter['number'] else ''
        heading = '' if chapter['title'] == 'Contents' else '<h2>%s%s</h2>' % (badge, html.escape(chapter['title']))
        sections.append('<div class="%s" id="%s">\n%s\n%s\n</div>\n'
                        % (css, chapter['anchor'] or 'contents', heading, body))

    page = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1f2937; line-height: 1.6; }
.container { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
.header { background: linear-gradient(135deg,#1d4ed8,#3b82f6); color: #fff; border-radius: 6px; padding: 26px; margin-bottom: 20px; }
.header h1 { font-size: 1.55rem; font-weight: 800; letter-spacing: -0.5px; }
.header p { margin-top: 8px; opacity: 0.92; font-size: 0.9rem; }
.section { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 20px 24px; margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.section h2 { font-size: 1.05rem; text-transform: uppercase; letter-spacing: 0.5px; color: #0f172a; margin-bottom: 14px; display: flex; align-items: center; gap: 10px; }
.section h3 { font-size: 0.95rem; color: #1e293b; margin: 20px 0 8px; padding-bottom: 5px; border-bottom: 1px solid #f1f5f9; }
.section h4 { font-size: 0.86rem; color: #334155; margin: 14px 0 6px; }
.num { display: inline-flex; align-items: center; justify-content: center; min-width: 26px; height: 26px; background: #1e293b; color: #f8fafc; border-radius: 4px; font-size: 0.78rem; font-weight: 700; }
p, li, td, th { font-size: 0.87rem; }
p { margin: 8px 0; }
ul, ol { margin: 8px 0 8px 22px; }
li { margin-bottom: 5px; }
table { width: 100%%; border-collapse: collapse; font-size: 0.82rem; margin: 10px 0; }
th { background: #f8fafc; text-align: left; padding: 8px 10px; border-bottom: 2px solid #e2e8f0; color: #374151; font-weight: 600; }
td { padding: 7px 10px; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
tr:last-child td { border-bottom: none; }
code { background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 3px; padding: 0 4px; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.8rem; }
pre { background: #0f172a; border-radius: 5px; padding: 14px 16px; overflow-x: auto; margin: 10px 0; }
pre code { background: none; border: none; padding: 0; color: #e2e8f0; font-size: 0.78rem; line-height: 1.55; white-space: pre; }
.k { color: #7dd3fc; } .v { color: #fbbf24; } .s { color: #86efac; } .c { color: #64748b; font-style: italic; } .kw { color: #c4b5fd; }
.callout { border-radius: 4px; padding: 11px 14px; margin: 12px 0; font-size: 0.85rem; background: #fffbeb; border: 1px solid #fcd34d; color: #92400e; }
.callout.ok { background: #f0fdf4; border-color: #86efac; color: #166534; }
.callout.info { background: #eff6ff; border-color: #bfdbfe; color: #1e40af; }
.callout.stop { background: #fef2f2; border-color: #fca5a5; color: #991b1b; }
.toc ul { columns: 2; column-gap: 32px; list-style: none; margin-left: 0; }
.toc li { break-inside: avoid; }
.toc p { font-weight: 700; color: #0f172a; margin-top: 14px; }
hr { border: none; border-top: 1px solid #e2e8f0; margin: 18px 0; }
.footer { text-align: center; color: #64748b; font-size: 0.78rem; padding: 18px 0; }
@media (max-width: 720px) { .toc ul { columns: 1; } .section { padding: 16px; } }
</style>
</head>
<body>
<div class="container">
<div class="header">
<h1>%s</h1>
<p>Complete documentation. The same content is maintained in <code>documentation.confluence</code> for publication to Confluence.</p>
</div>
%s
<div class="footer">DevSecOps Jenkins Shared Library</div>
</div>
</body>
</html>
""" % (html.escape(title), html.escape(title), '\n'.join(sections))
    target.write_text(page)
    print('written %s (%d bytes)' % (target, len(page)))


if __name__ == '__main__':
    main()
