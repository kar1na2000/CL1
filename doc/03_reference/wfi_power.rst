WFI and Power Management
========================

CL1 implements ``WFI`` with a sleep controller and optional clock gating.
The sleep controller runs on ``always_on_clock`` so that it can respond to
wakeup conditions while the core clock is stopped.

WFI Operation
-------------

When a valid ``WFI`` instruction reaches the ID/EX stage, instruction fetch is
stalled.  The sleep request is generated when the instruction reaches
writeback without a trap and no wakeup request is active:

.. code-block:: text

   wfi_sleep_req = wb_wfi && !wfi_wakeup_req

Before entering sleep, ``CL1PowerCtrl`` waits for the IFU, ICache, and DCache
idle indications.  ``core_sleep`` is asserted after all three are idle.  Cache
idle inputs are tied high when the corresponding cache interface is not used.

Wakeup
------

The wakeup request is generated from enabled pending machine interrupts and
debug state:

.. code-block:: text

   wfi_wakeup_req = irq_req_raw || debug_mode || debug_take_req

``irq_req_raw`` includes machine external, software, and timer interrupts whose
corresponding ``mie`` bits are enabled.  WFI wakeup does not depend on
``mstatus.MIE``.  Waking the core does not by itself cause trap entry; normal
interrupt acceptance still applies.

The external interrupt inputs are sampled into ``mip`` using
``always_on_clock``.  This allows an interrupt to wake the sleep controller
when the internal core clock is stopped.

Clock-Gating Configuration
--------------------------

Low-power options are defined in ``CL1PowerSaveConfig``.

.. list-table:: Low-power configuration
   :header-rows: 1
   :widths: 28 16 56

   * - Constant
     - Default
     - Description

   * - ``CKG_EN``
     - ``false``
     - Enables top-level core clock gating during WFI sleep.

   * - ``MODPOWERCFG``
     - ``false``
     - Enables the MDU, DCache, and LSU local clock-gating options.

   * - ``RF_NORESET``
     - ``true``
     - Omits reset initialization for registers ``x1`` through ``x31``.

With the default configuration, WFI can assert the internal ``core_sleep``
signal, but the top-level core clock remains running.  When ``CKG_EN`` is true,
``CL1Top`` disables the core clock while ``core_sleep`` is asserted.  The local
MDU, DCache, and LSU clock gates are independent of WFI sleep and are disabled
by default.
