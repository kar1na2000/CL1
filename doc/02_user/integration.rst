Integration
===========

The top-level module is ``CL1Top``.
It always exposes interrupt, debug request, and boot-address inputs:

* ``io_ext_irq`` for machine external interrupts,
* ``io_sft_irq`` for machine software interrupts,
* ``io_tmr_irq`` for machine timer interrupts,
* ``io_dbg_req_i`` for external debug requests, and
* ``io_boot_addr`` for the reset fetch address.

Drive ``io_boot_addr`` to a stable value before releasing reset.
The generated default is platform-specific, but the top-level port lets an SoC
or integration wrapper choose the actual reset vector.

In CoreBus mode, ``CL1Top`` exposes independent instruction and data buses:

* ``io_ibus`` for instruction fetch,
* ``io_dbus`` for load/store access.

This mode is useful for fast simulation and direct bus verification because no
cache or AXI fabric is instantiated.

In AXI mode, ``CL1Top`` exposes ``io_master``.
The internal memory path is:

.. code-block:: text

   IF/LSU -> optional ICache/DCache or bridges -> cache crossbar -> AXI master

When ``SOC_D64`` is selected by the full SoC profile, the top-level inserts an
AXI width converter so the external data bus can be wider than the core bus.

The reset policy is controlled by ``RST_ACTIVELOW`` and ``RST_ASYNC`` in the
processor configuration.
The current default is active-low asynchronous reset at the top-level boundary.
