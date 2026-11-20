#!/usr/bin/env python3
"""
BBH DevSecOps Documentation Renderer: Markdown to Enterprise HTML
Converts user-friendly markdown documentation into a self-contained,
high-quality, professional HTML documentation page with BBH styling.
"""

import sys
import re
import html
from pathlib import Path
import markdown

CSS_STYLE = """
:root {
  --primary: #0a2540;
  --primary-light: #1e3a8a;
  --primary-accent: #2563eb;
  --bg-main: #f8fafc;
  --bg-card: #ffffff;
  --border: #e2e8f0;
  --text-main: #1e293b;
  --text-muted: #64748b;
  --code-bg: #0f172a;
  --code-inline-bg: #f1f5f9;
  --tag-bg: #e0f2fe;
  --tag-text: #0369a1;
  --sidebar-w: 300px;
}

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  background-color: var(--bg-main);
  color: var(--text-main);
  line-height: 1.65;
  font-size: 15px;
}

.layout {
  display: flex;
  min-height: 100vh;
}

/* Sidebar Navigation */
.sidebar {
  width: var(--sidebar-w);
  background: #0f172a;
  color: #e2e8f0;
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  overflow-y: auto;
  padding: 24px 18px;
  border-right: 1px solid #1e293b;
  z-index: 100;
}

.sidebar-brand {
  font-size: 1.15rem;
  font-weight: 800;
  color: #38bdf8;
  letter-spacing: -0.3px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.sidebar-subbrand {
  font-size: 0.78rem;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 1px solid #334155;
}

.sidebar-nav {
  list-style: none;
}

.sidebar-nav li {
  margin-bottom: 6px;
}

.sidebar-nav a {
  display: block;
  color: #cbd5e1;
  text-decoration: none;
  font-size: 0.84rem;
  padding: 7px 10px;
  border-radius: 5px;
  transition: all 0.15s ease;
  line-height: 1.35;
}

.sidebar-nav a:hover {
  background: #1e293b;
  color: #38bdf8;
  transform: translateX(3px);
}

/* Main Content Area */
.main-wrapper {
  margin-left: var(--sidebar-w);
  flex: 1;
  max-width: calc(100% - var(--sidebar-w));
  padding: 36px 48px 80px;
}

.content-container {
  max-width: 1080px;
  margin: 0 auto;
}

/* Header Banner */
.doc-header {
  background: linear-gradient(135deg, #0a2540 0%, #1e3a8a 100%);
  color: #ffffff;
  padding: 36px 40px;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(10, 37, 64, 0.12);
  margin-bottom: 36px;
}

.doc-header h1 {
  font-size: 2.1rem;
  font-weight: 800;
  letter-spacing: -0.5px;
  margin-bottom: 10px;
}

.doc-header p {
  font-size: 1.02rem;
  color: #e0e7ff;
  max-width: 880px;
  line-height: 1.6;
}

.doc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
  font-size: 0.82rem;
}

.meta-chip {
  background: rgba(255, 255, 255, 0.15);
  padding: 4px 12px;
  border-radius: 20px;
  color: #ffffff;
  font-weight: 500;
}

/* Content Typography & Cards */
.doc-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 32px 36px;
  margin-bottom: 28px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

h2 {
  font-size: 1.45rem;
  font-weight: 700;
  color: var(--primary);
  margin-top: 24px;
  margin-bottom: 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid #e2e8f0;
  display: flex;
  align-items: center;
  gap: 10px;
}

h3 {
  font-size: 1.15rem;
  font-weight: 600;
  color: #1e3a8a;
  margin-top: 20px;
  margin-bottom: 10px;
}

h4 {
  font-size: 0.98rem;
  font-weight: 600;
  color: #334155;
  margin-top: 16px;
  margin-bottom: 8px;
}

p {
  margin-bottom: 12px;
}

ul, ol {
  margin: 10px 0 16px 24px;
}

li {
  margin-bottom: 6px;
}

/* Badges and Tags */
.badge {
  display: inline-block;
  padding: 2px 8px;
  font-size: 0.74rem;
  font-weight: 700;
  border-radius: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.badge-required { background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; }
.badge-optional { background: #f1f5f9; color: #475569; border: 1px solid #cbd5e1; }
.badge-default  { background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd; }
.badge-portal   { background: #dbeafe; color: #1e40af; border: 1px solid #93c5fd; }
.badge-keyless  { background: #fef3c7; color: #92400e; border: 1px solid #fde68a; }

/* Tables */
.table-wrapper {
  overflow-x: auto;
  margin: 16px 0 24px;
  border: 1px solid var(--border);
  border-radius: 6px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
  background: #ffffff;
}

th {
  background: #f8fafc;
  color: #334155;
  font-weight: 600;
  text-align: left;
  padding: 10px 14px;
  border-bottom: 2px solid var(--border);
  white-space: nowrap;
}

td {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  vertical-align: top;
}

tr:nth-child(even) td {
  background: #fcfcfd;
}

tr:last-child td {
  border-bottom: none;
}

/* Callouts / Alerts */
.callout {
  border-radius: 6px;
  padding: 16px 20px;
  margin: 18px 0;
  border-left: 4px solid;
  font-size: 0.9rem;
}

.callout-header {
  font-weight: 700;
  font-size: 0.82rem;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.callout-note {
  background: #eff6ff;
  border-left-color: #3b82f6;
  color: #1e40af;
}

.callout-tip {
  background: #f0fdf4;
  border-left-color: #22c55e;
  color: #166534;
}

.callout-important {
  background: #fffbeb;
  border-left-color: #f59e0b;
  color: #92400e;
}

.callout-warning {
  background: #fef2f2;
  border-left-color: #ef4444;
  color: #991b1b;
}

.callout-body p {
  margin-bottom: 6px;
}
.callout-body p:last-child {
  margin-bottom: 0;
}

/* Code Blocks */
code {
  background: var(--code-inline-bg);
  border: 1px solid #cbd5e1;
  color: #0f172a;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.84rem;
}

pre {
  background: var(--code-bg);
  border-radius: 6px;
  padding: 18px 22px;
  overflow-x: auto;
  margin: 14px 0 20px;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.3);
}

pre code {
  background: transparent;
  border: none;
  padding: 0;
  color: #f8fafc;
  font-size: 0.82rem;
  line-height: 1.55;
}

/* Footer */
.doc-footer {
  text-align: center;
  margin-top: 48px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 0.84rem;
}

@media (max-width: 900px) {
  .sidebar { display: none; }
  .main-wrapper { margin-left: 0; max-width: 100%; padding: 20px 16px; }
  .doc-header { padding: 24px 20px; }
  .doc-card { padding: 20px; }
}
"""

def render_markdown_to_html(md_path, html_path, title, subtitle):
    raw_md = Path(md_path).read_text(encoding="utf-8")

    # Extract headings for Table of Contents
    toc_items = []
    for line in raw_md.splitlines():
        m = re.match(r'^(#{2,3})\s+(.+)$', line)
        if m:
            level = len(m.group(1))
            h_text = m.group(2).strip()
            anchor = re.sub(r'[^\w\- ]', '', h_text).strip().lower().replace(' ', '-')
            toc_items.append((level, h_text, anchor))

    def alert_sub(m):
        atype = m.group(1).lower()
        content = re.sub(r'(?m)^>\s?', '', m.group(2)).strip()
        rendered_content = markdown.markdown(content)
        labels = {
            'note': 'NOTE',
            'tip': 'TIP',
            'important': 'IMPORTANT',
            'warning': 'WARNING',
            'caution': 'CAUTION'
        }
        lbl = labels.get(atype, atype.upper())
        return f'<div class="callout callout-{atype}"><div class="callout-header">{lbl}</div><div class="callout-body">{rendered_content}</div></div>\n'

    processed_md = re.sub(r'>\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*\n((?:>.*\n?)+)', alert_sub, raw_md)

    # Convert markdown to HTML body
    body_html = markdown.markdown(processed_md, extensions=['tables', 'fenced_code', 'toc'])

    # Wrap tables in table-wrapper
    body_html = re.sub(r'(<table>.*?</table>)', r'<div class="table-wrapper">\1</div>', body_html, flags=re.DOTALL)

    # Build Sidebar Navigation
    toc_html_list = []
    for level, h_text, anchor in toc_items:
        indent = "style='padding-left: 20px; font-size: 0.8rem;'" if level == 3 else ""
        clean_title = html.escape(re.sub(r'^[0-9\.\s]+', '', h_text))
        prefix = h_text.split(' ')[0] if '.' in h_text.split(' ')[0] else ''
        display_label = f"<span style='color:#94a3b8;font-weight:600;'>{prefix}</span> {clean_title}" if prefix else clean_title
        toc_html_list.append(f"<li><a href='#{anchor}' {indent}>{display_label}</a></li>")

    toc_nav = "\n".join(toc_html_list)

    full_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{html.escape(title)} - BBH DevSecOps</title>
  <style>{CSS_STYLE}</style>
</head>
<body>
  <div class="layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/></svg>
        BBH DevSecOps
      </div>
      <div class="sidebar-subbrand">{html.escape(subtitle)}</div>
      <ul class="sidebar-nav">
        {toc_nav}
      </ul>
    </aside>

    <main class="main-wrapper">
      <div class="content-container">
        <header class="doc-header">
          <h1>{html.escape(title)}</h1>
          <p>{html.escape(subtitle)}</p>
          <div class="doc-meta">
            <span class="meta-chip">Brown Brothers Harriman</span>
            <span class="meta-chip">DevSecOps Shared Library</span>
            <span class="meta-chip">Non-Technical User Guide</span>
            <span class="meta-chip">Enterprise Standard</span>
          </div>
        </header>

        <div class="doc-card">
          {body_html}
        </div>

        <footer class="doc-footer">
          <p>BBH DevSecOps Platform &bull; Technical &amp; User Documentation &bull; Confidential / Internal Use Only</p>
        </footer>
      </div>
    </main>
  </div>
</body>
</html>
"""
    Path(html_path).write_text(full_html, encoding="utf-8")
    print(f"Rendered: {html_path}")

if __name__ == '__main__':
    if len(sys.argv) < 5:
        print("Usage: render_doc.py <input.md> <output.html> <title> <subtitle>")
        sys.exit(1)
    render_markdown_to_html(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
