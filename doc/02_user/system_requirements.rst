System Requirements
===================

The recommended development environment is the repository Nix flake.
It provides the Scala/Chisel toolchain, the RISC-V embedded GCC toolchain, and
the documentation toolchain.

Core hardware build dependencies include:

* JDK 21,
* Mill,
* a RISC-V bare-metal GCC/GDB toolchain,
* SCons,
* device tree compiler,
* Spike, and
* Python with the helper modules used by the build scripts.

Documentation build dependencies include:

* Sphinx,
* the Read the Docs Sphinx theme,
* GNU Make,
* a LaTeX distribution with ``latexmk`` for PDF generation, and
* Poppler tools for optional PDF rendering checks.

Enter the development shell with:

.. code-block:: console

   nix develop

The shell hook starts an interactive ``zsh`` for normal terminal use.
