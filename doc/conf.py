# SPDX-License-Identifier: MulanPSL-2.0
#
# Sphinx configuration file for the CL1 Core documentation.

import os
import subprocess
from datetime import date

topsrcdir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

numfig = True
numfig_format = {
    "figure": "Figure %s",
    "table": "Table %s",
    "code-block": "Listing %s",
}

extensions = [
    "sphinx.ext.todo",
]

source_suffix = ".rst"
master_doc = "index"

project = "CL1 Core Documentation"
copyright = "2026, ECOS"
author = "ECOS"

version = ""
try:
    release = subprocess.check_output(
        ["git", "describe", "--tags", "--always", "--dirty"],
        cwd=topsrcdir,
        text=True,
    ).strip()
except Exception:
    release = "local"

language = "en"
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store", "venv"]
pygments_style = "sphinx"
todo_include_todos = True

html_theme = "sphinx_rtd_theme"
html_theme_options = {
    "style_nav_header_background": "#1f5f8b",
}
html_static_path = ["_static"]
html_css_files = ["theme_overrides.css"]

latex_documents = [
    (master_doc, "cl1-core-documentation.tex", "CL1 Core Documentation", author, "manual"),
]

today = date.today().strftime("%b %d, %Y")
today_fmt = "%b %d, %Y"

man_pages = [
    (master_doc, "cl1-core-documentation", "CL1 Core Documentation", [author], 1),
]

texinfo_documents = [
    (
        master_doc,
        "cl1-core-documentation",
        "CL1 Core Documentation",
        author,
        "cl1-core-documentation",
        "CL1 Core RV32 CPU core",
        "Miscellaneous",
    ),
]
