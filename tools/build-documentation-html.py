#!/usr/bin/env python3
"""Render documentation.confluence into documentation.html and documentation.md.

documentation.confluence is the single source of the library documentation. It is
written in Confluence wiki markup so that it can be pasted into Confluence without
conversion; this script renders the same content as a standalone HTML page and as
GitHub Flavored Markdown without raw HTML, which GitHub and Bitbucket display.

    python3 tools/build-documentation-html.py                  writes both files
    python3 tools/build-documentation-html.py --html <file>    writes only the HTML page there
    python3 tools/build-documentation-html.py --md <file>      writes only the Markdown there
    python3 tools/build-documentation-html.py <file>           as --html <file>, the former call
"""
import argparse
import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'documentation.confluence'
HTML_TARGET = ROOT / 'documentation.html'
MD_TARGET = ROOT / 'documentation.md'

PANELS = {'info': 'callout info', 'note': 'callout', 'warning': 'callout stop', 'tip': 'callout ok'}
PANEL_LABELS = {'info': 'Note', 'note': 'Note', 'warning': 'Warning', 'tip': 'Tip'}

KEYWORDS = ('projects', 'def', 'if', 'else', 'return', 'import', 'class', 'void', 'String', 'Map', 'List')

CODE = r'\{\{(.+?)\}\}(?!\})'
ANCHOR_LINK = r'\[([^\]|]+)\|(#[\w-]+)\]'
URL_LINK = r'\[([^\]|]+)\|(https?://[^\]]+)\]'
BOLD = r'(?<![\w*])\*([^*\n]+)\*(?![\w*])'
ITALIC = r'(?<![\w_])_([^_\n]+)_(?![\w_])'
LIST_ITEM = r'^([*#]+)\s+(.*)$'
BLOCK_START = r'^(h[1-5]\.|\|\||\{code|\{info|\{note|\{warning|\{tip|\{anchor|----|[*#]+\s)'


def split_row(line):
    """The cells of a table row; a | inside a code span or a link does not end a cell."""
    holds = []

    def hold(match):
        holds.append(match.group(0))
        return '\x04%d\x04' % (len(holds) - 1)

    text = line.strip()
    for pattern in (CODE, ANCHOR_LINK, URL_LINK):
        text = re.sub(pattern, hold, text)
    cells = re.split(r'(?<!\\)\|', text)[1:-1]
    for index in range(len(holds) - 1, -1, -1):
        cells = [c.replace('\x04%d\x04' % index, holds[index]) for c in cells]
    return cells


def parse(lines):
    """Split Confluence wiki markup into blocks that both renderers share."""
    blocks = []
    i = 0
    while i < len(lines):
        line = lines[i]

        match = re.match(r'^\{code(?::(\w+))?\}\s*$', line)
        if match:
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith('{code}'):
                body.append(lines[i])
                i += 1
            blocks.append(('code', match.group(1) or 'none', body))
            i += 1
            continue

        match = re.match(r'^\{(info|note|warning|tip)\}\s*$', line)
        if match:
            name = match.group(1)
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith('{%s}' % name):
                body.append(lines[i])
                i += 1
            blocks.append(('panel', name, [b for b in body if b.strip()]))
            i += 1
            continue

        match = re.match(r'^\{anchor:([\w-]+)\}\s*$', line)
        if match:
            blocks.append(('anchor', match.group(1)))
            i += 1
            continue

        match = re.match(r'^h([1-5])\.\s*(.+)$', line)
        if match:
            blocks.append(('heading', int(match.group(1)), match.group(2).strip()))
            i += 1
            continue

        if line.startswith('||'):
            head = re.split(r'\|\|', line.strip())[1:-1]
            rows = []
            i += 1
            while i < len(lines) and lines[i].startswith('|') and not lines[i].startswith('||'):
                rows.append(split_row(lines[i]))
                i += 1
            blocks.append(('table', head, rows))
            continue

        if re.match(r'^----+\s*$', line):
            blocks.append(('rule',))
            i += 1
            continue

        if re.match(LIST_ITEM, line):
            items = []
            while i < len(lines):
                nested = re.match(LIST_ITEM, lines[i])
                if not nested:
                    break
                items.append((len(nested.group(1)), nested.group(1)[-1], nested.group(2)))
                i += 1
            blocks.append(('list', items))
            continue

        if line.strip():
            block = [line]
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(BLOCK_START, lines[i]):
                block.append(lines[i])
                i += 1
            blocks.append(('para', block))
            continue
        i += 1
    return blocks


def chapters(lines):
    """The title and the chapters, each cut at {anchor:chN} followed by h1. N. Title."""
    title = re.sub(r'^h1\.\s*', '', lines[0]).strip()
    found = []
    current = {'anchor': '', 'number': '', 'title': 'Contents', 'lines': []}
    for line in lines[1:]:
        anchor = re.match(r'^\{anchor:(ch\d+)\}\s*$', line)
        if anchor:
            found.append(current)
            current = {'anchor': anchor.group(1), 'number': '', 'title': '', 'lines': []}
            continue
        heading = re.match(r'^h1\.\s*(\d+)\.\s*(.+)$', line)
        if heading and current['anchor'] and not current['title']:
            current['number'], current['title'] = heading.group(1), heading.group(2).strip()
            continue
        current['lines'].append(line)
    found.append(current)
    return title, found


def held(text, rules, escape):
    """Apply the inline rules in order; each match is held so later rules leave it alone."""
    holds = []

    def hold(markup):
        holds.append(markup)
        return '\x00%d\x00' % (len(holds) - 1)

    for pattern, markup in rules:
        text = re.sub(pattern, lambda m: hold(markup(m)), text)
    text = escape(text)
    for index in range(len(holds) - 1, -1, -1):
        text = text.replace('\x00%d\x00' % index, holds[index])
    return text


# ---------------------------------------------------------------- HTML

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
    return held(html.escape(text, quote=False), [
        (CODE, lambda m: '<code>%s</code>' % m.group(1)),
        (ANCHOR_LINK, lambda m: '<a href="%s">%s</a>' % (m.group(2), m.group(1))),
        (URL_LINK, lambda m: '<a href="%s" target="_blank" rel="noreferrer">%s</a>' % (m.group(2), m.group(1))),
        (BOLD, lambda m: '<strong>%s</strong>' % m.group(1)),
        (ITALIC, lambda m: '<em>%s</em>' % m.group(1)),
    ], lambda t: t)


def cell(text):
    return inline(text.replace('\\|', '\x01')).replace('\x01', '|').strip()


def render(lines):
    out = []
    for block in parse(lines):
        kind = block[0]
        if kind == 'code':
            out.append('<pre><code>%s</code></pre>' % colour(html.escape('\n'.join(block[2])), block[1]))
        elif kind == 'panel':
            out.append('<div class="%s">%s</div>' % (PANELS[block[1]], '<br>'.join(inline(b) for b in block[2])))
        elif kind == 'anchor':
            out.append('<a id="%s"></a>' % block[1])
        elif kind == 'heading':
            tag = {1: 'h2', 2: 'h3', 3: 'h4'}.get(block[1], 'h5')
            out.append('<%s>%s</%s>' % (tag, inline(block[2]), tag))
        elif kind == 'table':
            thead = ''.join('<th>%s</th>' % cell(c) for c in block[1])
            tbody = ''.join('<tr>%s</tr>' % ''.join('<td>%s</td>' % cell(c) for c in r) for r in block[2])
            out.append('<table><thead><tr>%s</tr></thead><tbody>%s</tbody></table>' % (thead, tbody))
        elif kind == 'rule':
            out.append('<hr>')
        elif kind == 'list':
            out.append(render_list(block[1]))
        else:
            out.append('<p>%s</p>' % inline(' '.join(block[1])))
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


def html_page(title, found):
    sections = []
    for chapter in found:
        body = render(chapter['lines'])
        if not body.strip():
            continue
        css = 'section toc' if chapter['title'] == 'Contents' else 'section'
        badge = '<span class="num">%s</span> ' % chapter['number'] if chapter['number'] else ''
        heading = '' if chapter['title'] == 'Contents' else '<h2>%s%s</h2>' % (badge, html.escape(chapter['title']))
        sections.append('<div class="%s" id="%s">\n%s\n%s\n</div>\n'
                        % (css, chapter['anchor'] or 'contents', heading, body))

    return """<!DOCTYPE html>
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
.section h5 { font-size: 0.84rem; color: #475569; margin: 12px 0 4px; }
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
<p>Complete documentation. The same content is maintained in <code>documentation.confluence</code> for publication to Confluence and published as <code>documentation.md</code> for Bitbucket.</p>
</div>
%s
<div class="footer">DevSecOps Jenkins Shared Library</div>
</div>
</body>
</html>
""" % (html.escape(title), html.escape(title), '\n'.join(sections))


# ---------------------------------------------------------------- Markdown

def md_escape(text):
    text = text.replace('\\', '\\\\')
    text = re.sub(r'([`*\[\]])', r'\\\1', text)
    text = re.sub(r'(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])', r'\\_', text)
    return text.replace('<', '&lt;').replace('>', '&gt;')


def md_code(text):
    fence = '`'
    while fence in text:
        fence += '`'
    pad = ' ' if text.startswith('`') or text.endswith('`') else ''
    return '%s%s%s%s%s' % (fence, pad, text, pad, fence)


def md_inline(text):
    return held(text, [
        (CODE, lambda m: md_code(m.group(1))),
        (ANCHOR_LINK, lambda m: '[%s](\x02%s\x03)' % (md_escape(m.group(1)), m.group(2)[1:])),
        (URL_LINK, lambda m: '[%s](%s)' % (md_escape(m.group(1)), m.group(2))),
        (BOLD, lambda m: '**%s**' % md_escape(m.group(1))),
        (ITALIC, lambda m: '*%s*' % md_escape(m.group(1))),
    ], md_escape)


def md_cell(text):
    return md_inline(text.replace('\\|', '\x01')).replace('|', '\\|').replace('\x01', '\\|').strip()


def md_guard(text):
    """Keep a paragraph or list text from starting a block of its own."""
    text = re.sub(r'^(\d+)([.)])(\s)', r'\1\\\2\3', text)
    return re.sub(r'^([#>+=-])', r'\\\1', text)


def plain(text):
    """The text a reader sees, from which GitHub and Bitbucket derive the heading anchor."""
    text = re.sub(CODE, lambda m: m.group(1), text)
    text = re.sub(ANCHOR_LINK, lambda m: m.group(1), text)
    text = re.sub(URL_LINK, lambda m: m.group(1), text)
    text = re.sub(BOLD, lambda m: m.group(1), text)
    return re.sub(ITALIC, lambda m: m.group(1), text)


class Slugger:
    """GitHub heading anchors: lower case, punctuation removed, spaces as hyphens, -1, -2 for repeats."""

    def __init__(self):
        self.seen = {}

    def slug(self, text):
        base = re.sub(r'[^\w\- ]', '', plain(text).strip().lower()).replace(' ', '-')
        count = self.seen.get(base, 0)
        self.seen[base] = count + 1
        return base if count == 0 else '%s-%d' % (base, count)


def md_list(items):
    out = []
    stack = []
    for level, marker, text in items:
        while stack and stack[-1]['level'] > level:
            stack.pop()
        if stack and stack[-1]['level'] == level and stack[-1]['marker'] == marker:
            stack[-1]['number'] += 1
        else:
            if stack and stack[-1]['level'] == level:
                stack.pop()
            stack.append({'level': level, 'marker': marker, 'number': 1})
        current = stack[-1]
        current['label'] = '%d.' % current['number'] if marker == '#' else '-'
        indent = sum(len(s['label']) + 1 for s in stack[:-1])
        out.append('%s%s %s' % (' ' * indent, current['label'], md_guard(md_inline(text))))
    return '\n'.join(out)


def md_blocks(lines, slugger, levels):
    out = []
    for block in parse(lines):
        kind = block[0]
        if kind == 'code':
            body = '\n'.join(block[2])
            fence = '```'
            while fence in body:
                fence += '`'
            out.append('%s%s\n%s\n%s' % (fence, 'text' if block[1] == 'none' else block[1], body, fence))
        elif kind == 'panel':
            paragraphs = [md_inline(b.strip()) for b in block[2]]
            if paragraphs:
                paragraphs[0] = '**%s:** %s' % (PANEL_LABELS[block[1]], paragraphs[0])
            out.append('\n>\n'.join('> ' + p for p in paragraphs))
        elif kind == 'heading':
            slugger.slug(block[2])
            out.append('%s %s' % ('#' * levels.get(block[1], 3), md_inline(block[2])))
        elif kind == 'table':
            head = '| %s |' % ' | '.join(md_cell(c) for c in block[1])
            rule = '|%s|' % '|'.join('---' for _ in block[1])
            rows = ['| %s |' % ' | '.join(md_cell(c) for c in r) for r in block[2]]
            out.append('\n'.join([head, rule] + rows))
        elif kind == 'rule':
            if out and out[-1] != '---':
                out.append('---')
        elif kind == 'list':
            out.append(md_list(block[1]))
        elif kind == 'para':
            out.append(md_guard(md_inline(' '.join(b.strip() for b in block[1]))))
    while out and out[-1] == '---':
        out.pop()
    return out


def md_page(title, found):
    slugger = Slugger()
    slugger.slug(title)
    anchors = {}
    parts = ['# %s' % md_inline(title),
             '*Complete documentation, generated from `documentation.confluence` by '
             '`tools/build-documentation-html.py`; the same content is published as `documentation.html` '
             'and in Confluence. Edit the source, not this file.*']
    for chapter in found:
        if chapter['title'] == 'Contents':
            parts.extend(md_blocks(chapter['lines'], slugger, {1: 2, 2: 2, 3: 3, 4: 4, 5: 5}))
            continue
        heading = '%s. %s' % (chapter['number'], chapter['title'])
        anchors[chapter['anchor']] = slugger.slug(heading)
        parts.append('## %s' % md_inline(heading))
        parts.extend(md_blocks(chapter['lines'], slugger, {1: 2, 2: 3, 3: 3, 4: 4, 5: 5}))
    page = '\n\n'.join(parts) + '\n'
    return re.sub('\x02([\\w-]+)\x03', lambda m: '#' + anchors.get(m.group(1), m.group(1)), page)


def write(target, text):
    with open(target, 'w', encoding='utf-8', newline='\n') as out:
        out.write(text)
    print('written %s (%d bytes)' % (target, len(text.encode('utf-8'))))


def main():
    parser = argparse.ArgumentParser(description='Render documentation.confluence into HTML and Markdown.')
    parser.add_argument('--html', type=Path, help='write only the HTML page, to this file')
    parser.add_argument('--md', type=Path, help='write only the Markdown, to this file')
    parser.add_argument('target', nargs='?', type=Path, help='the same as --html')
    args = parser.parse_args()
    args.html = args.html or args.target
    title, found = chapters(SOURCE.read_text(encoding='utf-8').split('\n'))
    both = not args.html and not args.md
    if args.html or both:
        write(args.html or HTML_TARGET, html_page(title, found))
    if args.md or both:
        write(args.md or MD_TARGET, md_page(title, found))


if __name__ == '__main__':
    main()
