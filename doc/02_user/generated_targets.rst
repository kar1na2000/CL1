Generated RTL Targets
=====================

The repository ``Makefile`` provides several Verilog generation targets.
They select the top-level interface, cache configuration, memory macro choice,
and optional verification ports for different integration flows.

Simulation Targets
------------------

``verilog-full-cache-axi`` and ``verilog-no-cache`` are intended for the
simple SoC simulation flow.
They generate RTL that is convenient for simulation and does not include
foundry-specific SRAM macro information.
In these targets, SRAMs remain simulation-oriented Chisel/generated memories
rather than technology-library SRAM instances.

``make verilog`` is an alias for ``verilog-full-cache-axi``.  This default
target generates ``Cl1Top`` with the instruction and data caches enabled and
an AXI master interface.
``verilog-no-cache`` generates the same simple SoC style of top level with the
instruction cache and data cache disabled.
The generated files are written to ``vsrc``.

Tapeout SoC Target
------------------

``verilog-full-soc-syn`` is the target used for the tapeout SoC.
It selects the full SoC platform and synthesis configuration, replacing the
simulation SRAM implementation with real foundry SRAM macros.
Use this target when the generated RTL is handed to the SoC implementation
flow rather than to the simple SoC simulator.

For compatibility with the current SoC integration, this target also adds the
AXI width converter that adapts the core-side 32-bit bus path to the 64-bit SoC
bus interface.

RVFI Targets
------------

Targets with an ``rvfi`` suffix generate processor-core variants for
riscv-formal integration.
They enable the RVFI top-level interface and select different interface/cache
shapes so the same core can be checked under multiple specifications:

.. list-table:: RVFI target variants
   :header-rows: 1

   * - Make target
     - Generated variant

   * - ``verilog-rvfi``
     - CoreBus-facing RVFI target with caches disabled.

   * - ``verilog-rvfi-axi``
     - AXI-facing RVFI target with caches disabled.

   * - ``verilog-rvfi-cache``
     - AXI-facing RVFI target with caches enabled.
