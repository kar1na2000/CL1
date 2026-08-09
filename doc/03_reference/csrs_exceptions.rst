CSRs, Privilege, Exceptions, and Interrupts
===========================================

CSR and Privilege Model
-----------------------

CL1 Core currently supports Machine mode.  The RVFI privilege mode is fixed to
M-mode for formal reporting.  ``CL1CSR`` implements machine CSRs, counters, and
read-only ID CSRs.

.. list-table:: Implemented M-mode CSRs
   :header-rows: 1

   * - CSR
     - Address
     - Behavior

   * - ``mstatus``
     - ``0x300``
     - Implements ``MIE``, ``MPIE``, and ``MPP``.  ``MPP`` is fixed to M-mode;
       other fields are fixed to zero.

   * - ``misa``
     - ``0x301``
     - Fixed to ``0x40001104`` for RV32IMC.

   * - ``mie``
     - ``0x304``
     - Supports ``MEIE``, ``MSIE``, and ``MTIE``.  Other interrupt-enable bits
       are zero.

   * - ``mtvec``
     - ``0x305``
     - Resets to platform ``TVEC_ADDR`` and supports direct and vectored modes.

   * - ``mscratch``
     - ``0x340``
     - Read/write scratch register.

   * - ``mepc``
     - ``0x341``
     - ``IALIGN=16``; bit 0 is hardwired to zero.

   * - ``mcause``
     - ``0x342``
     - Supports CSR writes and trap writes of exception or interrupt causes.

   * - ``mtval``
     - ``0x343``
     - Written on trap entry with instruction or address information for
       synchronous fault cases.

   * - ``mip``
     - ``0x344``
     - Samples external interrupt inputs into ``MEIP``, ``MSIP``, and ``MTIP``.

The counter CSRs ``mcycle``, ``minstret``, ``mcycleh``, and ``minstreth`` are
read/write registers.  ``mcycle`` increments each cycle unless explicitly
written, and ``minstret`` increments only when an instruction retires.
Instructions that raise synchronous exceptions, including ``ecall`` and
``ebreak``, do not increment ``minstret``.

The read-only ID CSRs ``mvendorid``, ``marchid``, ``mimpid``, ``mhartid``, and
``mconfigptr`` are present.  ``marchid`` is ``5`` and the other implemented ID
values are zero.

Decode checks CSR addresses and access permissions.  Access to a nonexistent
CSR or an attempted write to a read-only CSR raises an illegal-instruction
exception.  CSR writes are committed in WB.

Synchronous Exceptions
----------------------

Exception and interrupt handling is centralized in ``CL1EXCP``.  It receives
WB-stage synchronous exception reports, ``ECALL``, ``MRET``, ``WFI``, interrupt
state, and debug state.  It generates pipeline flushes, implicit CSR writes,
and WFI halt control.

The core implements precise synchronous exceptions.  ``mepc`` receives the PC
of the faulting instruction, ``mcause`` receives the exception code, and
``mtval`` receives the related instruction or address value.

Supported synchronous exception classes include illegal instruction,
breakpoint, load/store address misalignment, load/store access fault, and
M-mode ``ECALL``.  For illegal compressed instructions, ``mtval`` can hold the
zero-extended 16-bit compressed encoding.  For illegal 32-bit instructions,
``mtval`` can hold the original 32-bit instruction.  For misaligned access and
access-fault cases, ``mtval`` stores the related access address.

Interrupts
----------

Interrupt requests are formed from ``ext_irq``, ``sft_irq``, and ``tmr_irq``
together with the matching bits in ``mie`` and global ``mstatus.MIE``.
Interrupts are taken only when writeback is valid and there is no incomplete
memory operation that would affect precise commit.

.. list-table:: Supported interrupt causes
   :header-rows: 1

   * - Interrupt
     - ``mcause``

   * - Machine software interrupt
     - ``0x80000003``

   * - Machine timer interrupt
     - ``0x80000007``

   * - Machine external interrupt
     - ``0x8000000b``

If multiple interrupts are pending, priority is ``MEI > MSI > MTI``.  On
interrupt entry, ``mtval`` is written with zero, ``mstatus.MPIE`` receives the
old ``MIE`` value, and ``mstatus.MIE`` is cleared.

Trap Entry and Return
---------------------

``mtvec.base`` is formed from ``mtvec[31:2]`` with low bits ``00``.  In direct
mode, every trap redirects to base.  In vectored mode, interrupts redirect to
``base + 4 * cause`` while synchronous exceptions still redirect to base.

``MRET`` redirects the PC to ``mepc``, restores ``mstatus.MIE`` from
``mstatus.MPIE``, and sets ``mstatus.MPIE`` to one.

WFI
---

``WFI`` is decoded as a legal privileged instruction and is allowed to retire
without raising a synchronous trap.  Its sleep request is handled after the
instruction reaches the writeback point, while wakeup eligibility is generated
by the exception and interrupt logic.  See :doc:`wfi_power` for the detailed
WFI and power-management implementation.
