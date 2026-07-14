Debug, Diff, and RVFI Interfaces
================================

Debug
-----

``CL1Top`` exposes ``dbg_req_i`` as the external debug request input.
Internally, ``CL1DM`` coordinates debug entry, debug flushes, and the debug
state shared with CSR and exception logic.

The current debug entry and debug exception base constants are both
``0x800``.
When formal verification is enabled, debug-mode trap dispatch is constrained so
the RVFI model stays focused on architectural machine-mode behavior.

SoC Diff Port
-------------

When ``SOC_DIFF`` is enabled, ``CL1Top`` exposes ``diff_o`` with commit,
instruction, PC, privilege mode, destination register, and destination data
fields.
This port is intended for SoC-level differential testing.

RVFI
----

When ``CL1_FORMAL_VERIF=true``, ``CL1Top`` exposes a flattened RVFI interface.
The RVFI valid signal is derived from the writeback commit point.
The interface reports:

* instruction word and compressed-instruction selection,
* retire order,
* trap and interrupt status,
* source and destination register metadata,
* PC read/write data,
* load/store memory address, masks, and data, and
* selected CSR read/write channels.

For riscv-formal M-extension checks, use a target that also sets
``CL1_RISCV_FORMAL_ALTOPS=true``.
