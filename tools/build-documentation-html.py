#!/usr/bin/env python3
"""Render DOCUMENTATION.md into documentation.html with the library documentation style.

Usage: python3 tools/build-documentation-html.py [output.html]
"""
import html
import re
import sys
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'DOCUMENTATION.md'
DEFAULT_TARGET = ROOT / 'documentation.html'

STYLE = """* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1f2937; line-height: 1.55; }
.container { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
.header { background: linear-gradient(135deg,#1d4ed8,#3b82f6); color: #fff; border-radius: 6px; padding: 24px; margin-bottom: 20px; }
.header h1 { font-size: 1.6rem; font-weight: 800; letter-spacing: -0.5px; }
.header p { margin-top: 6px; opacity: 0.9; font-size: 0.9rem; }
.section { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 20px 24px; margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.section h2 { font-size: 1.05rem; text-transform: uppercase; letter-spacing: 0.5px; color: #0f172a; margin-bottom: 14px; display: flex; align-items: center; gap: 10px; }
.section h3 { font-size: 0.92rem; color: #1e293b; margin: 18px 0 8px; }
.section h4 { font-size: 0.85rem; color: #334155; margin: 14px 0 6px; }
.num { display: inline-flex; align-items: center; justify-content: center; min-width: 26px; height: 26px; background: #1e293b; color: #f8fafc; border-radius: 4px; font-size: 0.78rem; font-weight: 700; }
p, li { font-size: 0.87rem; }
p { margin: 8px 0; }
ul, ol { margin: 8px 0 8px 22px; }
li { margin-bottom: 4px; }
table { width: 100%; border-collapse: collapse; font-size: 0.82rem; margin: 10px 0; }
th { background: #f8fafc; text-align: left; padding: 8px 10px; border-bottom: 2px solid #e2e8f0; color: #374151; font-weight: 600; }
td { padding: 7px 10px; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
tr:last-child td { border-bottom: none; }
code { background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 3px; padding: 0 4px; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.8rem; }
pre { background: #0f172a; border-radius: 5px; padding: 14px 16px; overflow-x: auto; margin: 10px 0; }
pre code { background: none; border: none; padding: 0; color: #e2e8f0; font-size: 0.78rem; line-height: 1.5; white-space: pre; }
.k { color: #7dd3fc; } .v { color: #fbbf24; } .s { color: #86efac; } .c { color: #64748b; font-style: italic; } .kw { color: #c4b5fd; }
.callout { border-radius: 4px; padding: 10px 14px; margin: 12px 0; font-size: 0.85rem; background: #fffbeb; border: 1px solid #fcd34d; color: #92400e; }
.toc ol { columns: 2; column-gap: 32px; }
.footer { text-align: center; color: #64748b; font-size: 0.78rem; padding: 18px 0; }
@media (max-width: 720px) { .toc ol { columns: 1; } .section { padding: 16px; } }"""


def colour_yaml(code):
    out = []
    for line in code.split('\n'):
        comment = ''
        match = re.search(r'(\s+#.*)$', line)
        if match:
            comment = '<span class="c">%s</span>' % match.group(1)
            line = line[:match.start()]
        line = re.sub(r'^(\s*-?\s*)([\w.$<>-]+)(:)', r'\1<span class="k">\2</span>\3', line)
        line = re.sub(r'("[^"]*")', r'<span class="s">\1</span>', line)
        line = re.sub(r'(:\s+)(?!<span)([^\s#][^#<]*)$', r'\1<span class="v">\2</span>', line)
        out.append(line + comment)
    return '\n'.join(out)


def colour_code(code, language):
    if language == 'yaml':
        return colour_yaml(code)
    code = re.sub(r'(#[^\n<]*)', r'<span class="c">\1</span>', code) if language in ('bash', 'sh', '') else code
    code = re.sub(r"('[^'\n]*')", r'<span class="s">\1</span>', code)
    if language == 'groovy':
        code = re.sub(r'\b(def|return|if|else|for|new|import|class|static|void|true|false|null)\b', r'<span class="kw">\1</span>', code)
    return code


def render_code_blocks(text):
    blocks = []

    def store(match):
        language = (match.group(1) or '').strip()
        body = colour_code(html.escape(match.group(2)), language)
        blocks.append('<pre><code>%s</code></pre>' % body)
        return '\n\nCODEBLOCK%dPLACEHOLDER\n\n' % (len(blocks) - 1)

    text = re.sub(r'```(\w*)\n(.*?)```', store, text, flags=re.S)
    return text, blocks


def main():
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_TARGET
    text = SOURCE.read_text()

    title_line, rest = text.split('\n', 1)
    title = title_line.lstrip('# ').strip()
    intro = rest.strip().split('\n\n')[0].strip()
    rest = rest.split('\n\n', 1)[1]

    body, blocks = render_code_blocks(rest)
    sections = []
    current_title, current_number, buffer = 'Table of contents', '', []
    for line in body.split('\n'):
        heading = re.match(r'^## (?:(\d+)\.\s*)?(.+)$', line)
        if heading:
            sections.append((current_number, current_title, '\n'.join(buffer)))
            current_number = heading.group(1) or ''
            current_title = heading.group(2).strip()
            buffer = []
            continue
        buffer.append(line)
    sections.append((current_number, current_title, '\n'.join(buffer)))

    converter = markdown.Markdown(extensions=['tables', 'sane_lists'])
    parts = []
    for number, heading, content in sections:
        content = re.sub(r'^---\s*$', '', content, flags=re.M)
        content = re.sub(r'^\s*-\s*\[ \]\s*', '- ', content, flags=re.M)
        content = re.sub(r'(?m)^(?![-*#|>\s])(.+)\n(- |\d+\. )', r'\1\n\n\2', content)
        converter.reset()
        rendered = converter.convert(content.strip())
        rendered = re.sub(r'<blockquote>\s*<p>(.*?)</p>\s*</blockquote>', r'<div class="callout">\1</div>', rendered, flags=re.S)
        for index, block in enumerate(blocks):
            rendered = rendered.replace('<p>CODEBLOCK%dPLACEHOLDER</p>' % index, block)
            rendered = rendered.replace('CODEBLOCK%dPLACEHOLDER' % index, block)
        if not rendered.strip() and not number:
            continue
        css = 'section toc' if heading.lower() == 'table of contents' else 'section'
        badge = '<span class="num">%s</span> ' % number if number else ''
        slug = re.sub(r'[^a-z0-9 -]', '', ((number + '. ') if number else '') + heading.lower()).replace('. ', '-').replace(' ', '-')
        slug = re.sub(r'-+', '-', slug).strip('-')
        parts.append('<div class="%s" id="%s">\n  <h2>%s%s</h2>\n%s\n</div>\n' % (css, slug, badge, html.escape(heading), rendered))

    page = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s — Documentation</title>
<style>
%s
</style>
</head>
<body>
<div class="container">

<div class="header">
  <h1>%s</h1>
  <p>%s</p>
</div>

%s
<div class="footer">Generated from DOCUMENTATION.md by tools/build-documentation-html.py — run test/run-all.sh to regenerate.</div>
</div>
</body>
</html>
""" % (html.escape(title), STYLE, html.escape(title), html.escape(intro), '\n'.join(parts))
    target.write_text(page)
    print('written %s (%d bytes)' % (target, len(page)))


if __name__ == '__main__':
    main()
