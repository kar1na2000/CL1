Configuration
=============

CL1 Core configuration is centralized in ``cl1/src/scala/CL1Config.scala``.
Most options can be set through environment variables when running Mill or the
repository ``Makefile`` targets.

Main configuration variables:

``CL1_TEST_MODE``
   Default: ``bus``. Selects ``bus`` or ``cache`` mode. Make targets may
   override this.

``CL1_PLATFORM``
   Default: ``simple_soc``. Selects ``simple_soc`` or ``full_soc`` address
   profiles.

``CL1_ADDRESS_PROFILE``
   Default: unset. Compatibility alias for ``CL1_PLATFORM``.

``CL1_HAS_ICACHE``
   Default: derived from mode. Enables the instruction cache when the top level
   does not expose CoreBus.

``CL1_HAS_DCACHE``
   Default: derived from mode. Enables the data cache when the top level does
   not expose CoreBus.

``CL1_EXPOSE_CORE_BUS``
   Default: ``true`` in bus mode. Exposes instruction and data ``CoreBus``
   ports instead of the AXI master.

``CL1_SYN``
   Default: derived from mode. Enables synthesis/foundry SRAM choices.

``CL1_TECHNOLOGY``
   Default: ``CX55``. Selects ``CX55``, ``SMIC55``, or ``SMIC100`` memory
   macros.

``CL1_SOC_DIFF``
   Default: derived from platform. Enables the optional SoC differential trace
   port.

``CL1_FORMAL_VERIF``
   Default: ``false``. Enables the RVFI top-level port and formal-friendly
   logic.

``CL1_RISCV_FORMAL_ALTOPS``
   Default: ``false``. Uses riscv-formal alternative operations for M-extension
   checking.

``CL1_FORMAL_CACHE_IDXW``
   Default: ``7``. Sets the formal cache index width used by cache formal
   targets.

Some configuration combinations are intentionally rejected.
For example, exposing CoreBus while enabling caches is invalid because the
cache instances would be unreachable.

The active platform controls the default boot address and trap vector:

``simple_soc``
   Boot address ``0x80000000`` and trap vector ``0x20000000``. Provides a
   basic RAM and MMIO map.

``full_soc``
   Boot address ``0x01000000`` and trap vector ``0x20000000``. Provides the
   full SoC memory map with ISRAM, DSRAM, SDRAM, PLIC, CLINT, UARTs, DMA, and
   peripherals.
