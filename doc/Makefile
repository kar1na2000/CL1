# Minimal makefile for Sphinx documentation

SPHINXOPTS    =
SPHINXBUILD   = sphinx-build
SPHINXPROJ    = cl1_core
SOURCEDIR     = .
BUILDDIR      = _build
DOC_SOURCE_DATE_EPOCH := $(shell date +%s)

help:
	@SOURCE_DATE_EPOCH=$(DOC_SOURCE_DATE_EPOCH) $(SPHINXBUILD) -M help "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)

.PHONY: help Makefile

%: Makefile
	@SOURCE_DATE_EPOCH=$(DOC_SOURCE_DATE_EPOCH) $(SPHINXBUILD) -M $@ "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)
