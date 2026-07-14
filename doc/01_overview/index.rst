Introduction to CL1 Core
========================

CL1 Core is a Chisel implementation of a 32-bit RISC-V processor.
It is organized around a compact in-order pipeline, optional instruction and
data caches, an AXI-facing top level for SoC integration, and a CoreBus-facing
mode for direct verification.

Read this section for the high-level properties of CL1 Core: which RISC-V
features it implements, which generated targets are maintained, and how the
verification flow is structured.

.. toctree::
   :maxdepth: 2
   :caption: In this section

   compliance
   targets
   verification_overview
