#!/usr/bin/env python3
"""Migrate docs HTML pages to shared design-system CSS/JS."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # docs/

STYLE_RE = re.compile(r"<style\b[^>]*>.*?</style>\s*", re.I | re.S)
SITE_JS_RE = re.compile(r'<script\s+src="[^"]*site\.js"[^>]*>\s*</script>\s*', re.I)
INLINE_SCRIPT_TOGGLE = re.compile(
    r"<script>\s*//\s*Mobile menu.*?</script>\s*",
    re.I | re.S,
)


def depth_prefix(path: Path) -> str:
    rel = path.relative_to(ROOT)
    depth = len(rel.parts) - 1
    return "../" * depth if depth else ""


def inject_assets(html: str, path: Path, css_name: str) -> str:
    prefix = depth_prefix(path)
    css_href = f"{prefix}assets/css/{css_name}"
    js_href = f"{prefix}assets/js/site.js"
    font_link = (
        '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
        "family=Outfit:wght@400;500;600;700;800&family=Source+Sans+3:ital,wght@0,400;0,500;0,600;0,700;1,400"
        '&family=JetBrains+Mono:wght@400;500&display=swap">\n'
    )
    link_tag = f'<link rel="stylesheet" href="{css_href}">\n'
    js_tag = f'<script src="{js_href}"></script>\n'

    html = STYLE_RE.sub("", html)

    # Remove old _shared.css link if present (will re-add via docs.css path)
    html = re.sub(
        r'<link\s+rel="stylesheet"\s+href="[^"]*_shared\.css"\s*/?>\s*',
        "",
        html,
        flags=re.I,
    )

    if css_href not in html and f"assets/css/{css_name}" not in html:
        if "</head>" in html.lower():
            html = re.sub(
                r"</head>",
                font_link + link_tag + "</head>",
                html,
                count=1,
                flags=re.I,
            )
        else:
            html = font_link + link_tag + html

    # Ensure site-bg after body
    if 'class="site-bg"' not in html:
        html = re.sub(
            r"(<body[^>]*>)",
            r'\1\n<div class="site-bg" aria-hidden="true"></div>',
            html,
            count=1,
            flags=re.I,
        )

    # Docs sidebar pages
    if css_name == "docs.css" and "page-wrapper" in html:
        html = re.sub(
            r"<body([^>]*)>",
            lambda m: f'<body class="has-sidebar"{m.group(1)}>'
            if "has-sidebar" not in m.group(1)
            else f"<body{m.group(1)}>",
            html,
            count=1,
            flags=re.I,
        )
        # Fix class merge if body already has attributes
        html = re.sub(
            r'<body class="has-sidebar"(\s+class="[^"]*")',
            r"<body class=\"has-sidebar\"",
            html,
            count=1,
        )
        html = re.sub(
            r'<body([^>]*?)class="([^"]*)"',
            lambda m: m.group(0)
            if "has-sidebar" in m.group(2)
            else f'<body{m.group(1)}class="has-sidebar {m.group(2)}"',
            html,
            count=1,
            flags=re.I,
        )

    # Strip bulky legacy mobile-menu scripts (site.js covers this)
    html = INLINE_SCRIPT_TOGGLE.sub("", html)
    # Remove duplicate function toggleMobileMenu scripts that are self-contained at end
    html = re.sub(
        r"<script>\s*function\s+toggleMobileMenu\s*\(\)\s*\{.*?</script>\s*",
        "",
        html,
        flags=re.I | re.S,
    )
    html = re.sub(
        r"<script>\s*document\.addEventListener\('DOMContentLoaded'.*?toggleMobileMenu.*?</script>\s*",
        "",
        html,
        flags=re.I | re.S,
    )

    if "assets/js/site.js" not in html:
        if re.search(r"</body>", html, re.I):
            html = re.sub(r"</body>", js_tag + "</body>", html, count=1, flags=re.I)
        else:
            html += js_tag

    return html


def migrate_file(path: Path, css_name: str) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = inject_assets(original, path, css_name)
    if updated != original:
        path.write_text(updated, encoding="utf-8", newline="\n")
        return True
    return False


def main() -> None:
    changed = []

    # Tool docs already on _shared.css
    for p in (ROOT / "docs").rglob("*.html"):
        if p.name == "index.html" and p.parent == ROOT / "docs":
            # docs hub handled separately
            continue
        css = "docs.css"
        if migrate_file(p, css):
            changed.append(str(p.relative_to(ROOT)))

    # Docs hub
    hub = ROOT / "docs" / "index.html"
    if hub.exists() and migrate_file(hub, "design-system.css"):
        changed.append("docs/index.html")

    # Host/HSM simulators with inline CSS
    for name in ("host-simulator", "hsm-simulator"):
        p = ROOT / "docs" / name / "index.html"
        if p.exists() and migrate_file(p, "docs.css"):
            changed.append(str(p.relative_to(ROOT)))

    # Blog posts + index
    for p in (ROOT / "blogs").rglob("*.html"):
        if migrate_file(p, "blog.css"):
            changed.append(str(p.relative_to(ROOT)))

    # Legal
    for name in ("privacy-policy.html", "terms-and-conditions.html"):
        p = ROOT / name
        if p.exists() and migrate_file(p, "design-system.css"):
            changed.append(name)

    print(f"Migrated {len(changed)} files")
    for c in changed[:40]:
        print(" -", c)
    if len(changed) > 40:
        print(f" ... and {len(changed) - 40} more")


if __name__ == "__main__":
    main()
