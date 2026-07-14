Microarchitecture
=================

Pipeline Structure
------------------

The main pipeline is ``IF -> ID/EX -> WB`` when ``WB_PIPESTAGE=true``, which is
the default configuration.  ``CL1Core.scala`` connects the stages, register
file, CSR block, exception unit, debug controller, LSU, MDU, and optional
cache/AXI path.

.. code-block:: text

   CoreBus/ICache
         |
   FetchAlign <-> CL1IFStage <-> CL1BPU
                    |
             PipelineConnect
                    |
              CL1IDEXStage -> CL1ALU / CL1MDULp / CL1LSU request
                    |          CSR read / branch resolve
             PipelineConnect
                    |
               CL1WBStage -> GPR write / CSR write / LSU response
                              CL1EXCP / CL1DM

The stage roles are:

``IF``
   Main modules: ``CL1IFStage``, ``FetchAlign``, ``CL1RVCExpander``, and
   ``CL1BPU``.  This stage starts fetching from ``BOOT_ADDR``, maintains the
   PC, handles BPU redirects, expands 16-bit RVC instructions, and forwards the
   expanded 32-bit instruction with compressed-instruction metadata.

``ID/EX``
   Main modules: ``CL1IDEXStage``, ``Cl2Decoder``, ``CL1ALU``, and
   ``CL1MDULp``.  This stage decodes RV32I/M/C/Zicsr instructions, generates
   operands and immediates, executes single-cycle ALU, branch, and CSR
   read-modify-write operations, issues multi-cycle LSU/MDU requests, and
   detects illegal instructions or misaligned memory accesses.

``WB``
   Main module: ``CL1WBStage``.  This stage selects ALU, CSR, and LSU
   writeback data, writes the GPR and CSR files, receives memory responses, and
   reports commit state to exception and debug logic.

``FetchAlign.scala`` handles instruction alignment.  The fetch bus is 32 bits
wide.  If the PC points to the upper halfword and the instruction crosses a
32-bit boundary, the aligner issues a second fetch and concatenates the
instruction.  Sequential 16-bit instructions can reuse the buffered upper
halfword and avoid an additional bus access.

In CoreBus mode, instruction and data traffic leave the core through ``ibus``
and ``dbus``.  In cache/AXI mode, the IF path can use ``CL1ICACHE`` and the LSU
path can use ``CL1DCACHE``; both paths then share the internal ``CacheBus``
crossbar and AXI bridge.

Structural Hazards
------------------

Structural hazards are handled with ready/valid backpressure, local state
machines, and bus arbitration.

.. list-table:: Structural hazard handling
   :header-rows: 1

   * - Resource
     - Handling

   * - LSU
     - ``CL1LSU`` tracks one outstanding data access.  While waiting for a
       response, it does not accept an independent new request.  If the current
       response and a new request handshake in the same cycle, the LSU remains
       in the waiting state and transfers the outstanding slot to the new
       request.

   * - MDU
     - ``CL1MDULp`` is a single iterative multiply/divide unit.  When the MDU is
       busy or its result has not been accepted by WB, ID/EX holds the current
       multiply/divide instruction.

   * - Instruction/data external path
     - In cache/AXI mode, ICache and DCache share the AXI4 path through
       ``CacheBusCrossbarNto1``.  In CoreBus mode, the instruction and data
       paths are exposed independently at the core boundary.

Data Hazards
------------

GPR RAW hazards are detected in ``CL1Core.scala``.  If ID/EX reads ``rs1`` or
``rs2`` and WB will write the same nonzero ``rd`` while WB is valid, the core
raises ``rs1Hazard`` or ``rs2Hazard``.

With the default ``WB_PIPESTAGE=true`` setting:

* Non-load producers are forwarded from ``wbStage.forwardDat`` into the ID/EX
  operand path.
* Load producers stall ID/EX through ``dx_stall`` until the LSU response reaches
  WB and the load can write back.
* Store addresses and store data use the same ``rs1``/``rs2`` forwarding and
  stall logic.
* WAW and WAR hazards are avoided by single issue and ordered commit.

The current ``rs1_ren`` and ``rs2_ren`` signals are derived from whether the
instruction fields name ``x0``.  They do not fully distinguish whether the
instruction semantically uses each source field, so some instructions can
trigger conservative forwarding or stalls.  This does not affect functional
correctness.

CSR RAW hazards use a conservative stall policy.  If the current ID/EX
instruction accesses a CSR and WB has a pending CSR write commit, ID/EX stalls.
The current logic does not compare CSR addresses, so writes to different CSRs
can still produce a conservative stall.

CSR instruction data is formed in ID/EX.  ``CSRRW`` writes ``rs1`` or ``uimm``;
``CSRRS`` sets bits with OR; ``CSRRC`` clears bits by ANDing the old CSR value
with the inverted ``rs1`` or ``uimm`` mask.  The CSR read value becomes
``rdWdat`` for WB, and the new CSR value becomes ``csrWdat`` for WB commit.

Control Hazards
---------------

Control hazards are managed by static prediction in IF, real branch resolution
in ID/EX, and redirect handling in IF.

``CL1BPU.scala`` implements the prediction policy:

.. list-table:: Branch prediction policy
   :header-rows: 1

   * - Instruction type
     - Prediction

   * - ``jal``, ``c.j``, ``c.jal``
     - Predict taken.

   * - B-type conditional branches
     - Use the immediate sign bit as a static direction hint: negative offsets
       predict taken, positive offsets predict not taken.

   * - ``jalr x0``
     - May predict an absolute-offset target.

   * - ``jalr x1``, ``c.jr`` / ``c.jalr x1``
     - Use the register-file value of ``x1`` when ``x1`` is not waiting on a
       younger write in ID/EX or WB.

The real branch outcome is computed in ID/EX from the ALU ``eq`` and ``lt``
signals and decoded jump type.  When the predicted direction differs from the
real direction, ``brchmis_flush_pluse`` redirects IF for one cycle.  Taken
redirects target ``pc + imm`` or ``rs1 + imm``; not-taken redirects target
``pc + instr_size``.  IF discards stale fetch responses and, if an old fetch is
still outstanding, sets ``flush_pending`` until the new request is issued.

Execution Units
---------------

``CL1ALU.scala`` is a combinational ALU for
``ADD``, ``SUB``, ``AND``, ``OR``, ``XOR``, ``SLL``, ``SLT``, ``SLTU``,
``SRL``, and ``SRA``.  It also produces ``eq`` and ``lt`` for branch
resolution.

The ALU uses a 35-bit adder for addition, subtraction, and comparison.
``SLT`` and ``SLTU`` use subtraction sign and borrow information.  Left shifts
reuse right-shift logic through bit reversal.  ``MDU_SHAERALU`` allows the MDU
to reuse the ALU adder, but the default configuration leaves this disabled.

``CL1MDULp.scala`` implements the RV32M operations
``MUL``, ``MULH``, ``MULHSU``, ``MULHU``, ``DIV``, ``DIVU``, ``REM``, and
``REMU``.  It is an iterative ready/valid unit.  ID/EX sends requests through
``mdu_in.valid`` and observes completion through ``mdu_out.fire``.

The following latencies describe the MDU control path when ``mdu_in.valid``
starts a calculation, ``mdu_out.ready=1``, and no flush occurs.

.. list-table:: MDU calculation latency
   :header-rows: 1

   * - Operation
     - Calculation latency

   * - ``MUL`` / ``MULH`` / ``MULHSU`` / ``MULHU``
     - About 17 iterative cycles.

   * - ``DIV`` / ``DIVU`` / ``REM`` / ``REMU``, no correction needed
     - About 34 cycles.

   * - ``DIV`` / ``DIVU`` / ``REM`` / ``REMU``, correction needed
     - About 36 cycles.

   * - Divide by zero
     - Same-cycle response, with no main iteration.

The multiplier counter runs from 0 through 16, giving 17 Booth iteration
cycles.  The divider counter runs from 0 through 32 for 33 main iterations,
then passes through remainder checking and optional quotient/remainder
correction.  Divide-by-zero uses the special-case path and raises ``mdu_oen``
without entering the main iterative state.  A back-to-back result reuse path
exists in the MDU, but ``CL1IDEXStage.scala`` currently ties
``mdu_in.bits.b2b`` to ``false.B``.
