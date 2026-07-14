CL1 Core: a 32-bit RISC-V CPU core
==================================

CL1 Core is a 32-bit RISC-V processor core written in Chisel.
The core targets RV32IMC with Zicsr, exposes configurable bus shapes for
integration and verification, and includes configurable generated RTL targets.

You are now reading the CL1 Core documentation.
The documentation is split into three parts.

The :doc:`Overview documentation <01_overview/index>` describes the core at a
high level: what ISA profile it implements, what integration targets it
supports, and how verification is organized.

The :doc:`User Guide <02_user/index>` describes how to build and configure
CL1 Core.
It is aimed at hardware developers integrating the generated RTL and software
developers preparing bare-metal images for the core.

The :doc:`Reference Guide <03_reference/index>` provides the CL1 Core design
specification.
It documents the top-level interfaces, pipeline, memory and cache system, CSR
and exception behavior, debug interfaces, and RVFI hooks.

.. toctree::
   :maxdepth: 2
   :hidden:

   01_overview/index.rst
   02_user/index.rst
   03_reference/index.rst
