Standards Compliance
====================

CL1 Core implements a 32-bit RISC-V machine-mode processor profile.
The default software-visible ISA is RV32IMC_Zicsr:

.. list-table:: Implemented ISA features
   :header-rows: 1

   * - Feature
     - Status
     - Notes

   * - RV32I base integer ISA
     - Implemented
     - Integer register file, branches, jumps, loads, stores, and integer ALU operations.

   * - M extension
     - Implemented
     - Multiplication and division are handled by the MDU.

   * - C extension
     - Implemented
     - Compressed instructions are expanded before decode; IALIGN is 16 bits.

   * - Zicsr extension
     - Implemented
     - Machine-mode CSR reads and writes are supported.

   * - Machine mode
     - Implemented
     - CL1 Core exposes machine external, software, and timer interrupt inputs.

The CSR block reports ``misa = 0x40001104``, corresponding to RV32 with the
I, M, and C extensions.
The implemented machine CSRs include ``mstatus``, ``misa``, ``mie``, ``mtvec``,
``mscratch``, ``mepc``, ``mcause``, ``mtval``, ``mip``, ``mcycle``,
``minstret``, ``mcycleh``, and ``minstreth``.

The core also contains debug-mode control logic and a debug request input.
The debug CSRs used by the internal debug controller are present in the CSR
implementation, while SoC-level debug module integration is left to the
surrounding system.

Privilege and trap behavior is centered on machine mode.
The CL1 Core supports both direct and vectored modes for ``mtvec``.
Because compressed instructions are enabled, ``mepc[0]`` is hardwired to zero.
