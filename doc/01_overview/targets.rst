Targets and Generated Interfaces
================================

CL1 Core has two primary generated interface styles.
They are selected with ``CL1_TEST_MODE`` and then refined by the individual
generation targets in the repository ``Makefile``.

.. list-table:: Main build modes
   :header-rows: 1

   * - Mode
     - Interface
     - Typical use

   * - ``bus``
     - Exposed instruction and data ``CoreBus`` ports
     - Fast simulation, direct bus verification, and RVFI no-cache targets.

   * - ``cache``
     - AXI4 master port
     - SoC integration, cache verification, and full-system simulation.

The maintained Verilog targets are:

.. list-table:: Repository targets
   :header-rows: 1

   * - Make target
     - Description

   * - ``make verilog``
     - Default full core with ICache, DCache, and AXI master interface.

   * - ``make verilog-full-cache-axi``
     - Explicit form of the default cache/AXI target.

   * - ``make verilog-full-soc-syn``
     - Full SoC synthesis target using CX55 technology SRAM macros.

   * - ``make verilog-full-soc-diff``
     - Full SoC verification target with the SoC diff port enabled.

   * - ``make verilog-no-cache``
     - AXI master interface without ICache or DCache.

   * - ``make verilog-rvfi``
     - RVFI target with CoreBus exposed and caches disabled.

   * - ``make verilog-rvfi-axi``
     - RVFI target with AXI exposed and caches disabled.

   * - ``make verilog-rvfi-cache``
     - RVFI target with AXI exposed and caches enabled.

``CL1_PLATFORM`` selects the address profile.
The default ``simple_soc`` profile boots at ``0x80000000`` and provides a
small RAM/MMIO map for simulation.
The ``full_soc`` profile boots at ``0x01000000`` and models the larger SoC
address map used by full-system integration.
