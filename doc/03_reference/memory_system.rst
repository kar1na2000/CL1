Memory and Cache System
=======================

CL1 Core has separate instruction-fetch and data-access paths internally.  The
top level selects either direct CoreBus exposure or cache/AXI mode through
``EXPOSE_CORE_BUS``.

The memory interface modes are:

CoreBus direct mode
   Top-level interface: ``ibus`` and ``dbus``.
   Internal path: ``FetchAlign -> ibus`` and ``CL1LSU -> dbus``.
   Typical use: basic verification, simple SoC integration, and formal
   variants.

Cache/AXI mode
   Top-level interface: ``master`` AXI4.
   Internal path: ``FetchAlign`` and ``CL1LSU`` connect to ICache/DCache or
   ``CoreBus2CacheBus``, then through ``CacheBusCrossbar`` and ``CacheBus2Axi4``
   to AXI4.
   Typical use: cache verification and AXI SoC integration.

``CL1Config.scala`` controls the active configuration.  With the default
``CL1_TEST_MODE=bus``, ``EXPOSE_CORE_BUS=true`` and caches are not
instantiated.  With ``CL1_TEST_MODE=cache``, ``EXPOSE_CORE_BUS=false``, ICache
and DCache are enabled by default, and the top level exports AXI4.  The
configuration system rejects unreachable combinations such as
``EXPOSE_CORE_BUS=true`` with caches enabled.

Cacheable address attributes come from the selected platform map in
``AddressMap.scala`` and ``MemoryMap.scala``.  The fetch side uses
``MemoryMap.isICacheable(addr)`` and the data side uses
``MemoryMap.isDCacheable(addr)``.  Cacheable regions use cache-line
refill/writeback behavior.  Non-cacheable regions use single bus transactions
and are primarily used for MMIO.

Platform cacheability is:

``simple_soc``
   Cacheable regions: ``ram`` at ``0x80000000`` with size ``0x01000000`` is
   instruction- and data-cacheable.  Non-cacheable and MMIO regions include
   ``debug`` at ``0x00000000``, ``uart`` at ``0x10000000``, and ``host_exit`` at
   ``0x10000004``.

``full_soc``
   Cacheable regions include ``isram`` at ``0x01000000`` for instruction and
   data, ``dsram`` at ``0x01800000`` for data, ``qspi_mem`` at ``0x20000000``
   for instruction and data, and ``sdram`` at ``0x80000000`` for instruction
   and data.  Non-cacheable and MMIO regions include PLIC, CLINT, UART, timer,
   GPIO, QSPI registers, CRU, debug, I2C, and related peripheral windows.

LSU
---

``CL1LSU.scala`` implements the data access unit.  ID/EX computes the memory
address, the LSU converts load/store operations into ``CoreBus`` requests, and
WB receives the returned load data or memory error status.

.. list-table:: LSU interfaces
   :header-rows: 1

   * - Interface
     - Direction
     - Description

   * - ``io.in.req``
     - ID/EX to LSU
     - Memory request with ``memType``, address, and store data.

   * - ``io.in.resp``
     - LSU to WB
     - Memory response with load data and error indication.

   * - ``io.out``
     - LSU to memory system
     - ``CoreBus`` data access interface.

   * - ``io.flush``
     - Core to LSU
     - Drops uncommitted responses after exception, debug, or branch flushes.

``memType`` is a 4-bit encoding.  Bit 3 selects load or store, bits 2:1 encode
the access width, and bit 0 selects signed or unsigned load extension.

.. list-table:: LSU memory type encoding
   :header-rows: 1

   * - Instruction
     - ``memType``
     - Width
     - Load extension

   * - ``LB``
     - ``0010``
     - 1 byte
     - Sign extension.

   * - ``LBU``
     - ``0011``
     - 1 byte
     - Zero extension.

   * - ``LH``
     - ``0100``
     - 2 bytes
     - Sign extension.

   * - ``LHU``
     - ``0101``
     - 2 bytes
     - Zero extension.

   * - ``LW``
     - ``0110``
     - 4 bytes
     - Returned unchanged.

   * - ``SB``
     - ``1010``
     - 1 byte
     - Not applicable.

   * - ``SH``
     - ``1100``
     - 2 bytes
     - Not applicable.

   * - ``SW``
     - ``1110``
     - 4 bytes
     - Not applicable.

Misaligned accesses are detected in ID/EX before the LSU accepts the request.

.. list-table:: Alignment requirements
   :header-rows: 1

   * - Access width
     - Required alignment

   * - 1 byte
     - Any byte address.

   * - 2 bytes
     - ``addr[0] == 0``.

   * - 4 bytes
     - ``addr[1:0] == 0``.

.. list-table:: Memory access exceptions
   :header-rows: 1

   * - Exception
     - Trigger
     - ``mcause``
     - ``mtval``

   * - Load address misaligned
     - Misaligned load
     - ``4``
     - Access address.

   * - Store/AMO address misaligned
     - Misaligned store
     - ``6``
     - Access address.

   * - Load access fault
     - Load response error
     - ``5``
     - Access address.

   * - Store/AMO access fault
     - Store response error
     - ``7``
     - Access address.

The LSU generates ``CoreBus.req`` fields as follows.

.. list-table:: LSU CoreBus request generation
   :header-rows: 1

   * - CoreBus field
     - Generation rule

   * - ``addr``
     - Byte address calculated in ID/EX.

   * - ``wen``
     - ``memType[3]``.

   * - ``cache``
     - ``MemoryMap.isDCacheable(addr)``.

   * - ``size``
     - ``1B -> 0``, ``2B -> 1``, ``4B -> 2``.

   * - ``mask``
     - Derived from address bits ``[1:0]`` and access width.

   * - ``data``
     - Store data shifted into the target byte lanes.

.. list-table:: Byte mask generation
   :header-rows: 1

   * - Access
     - Low address bits
     - ``mask``

   * - 1 byte
     - ``00``
     - ``0001``

   * - 1 byte
     - ``01``
     - ``0010``

   * - 1 byte
     - ``10``
     - ``0100``

   * - 1 byte
     - ``11``
     - ``1000``

   * - 2 bytes
     - ``00``
     - ``0011``

   * - 2 bytes
     - ``10``
     - ``1100``

   * - 4 bytes
     - ``00``
     - ``1111``

Load responses use the saved ``memType`` and ``mask`` from the request
handshake to select byte or halfword data, then sign-extend or zero-extend to
32 bits as required.

The LSU tracks one outstanding transaction.  Its state machine is:

.. list-table:: LSU state machine
   :header-rows: 1

   * - State
     - Description

   * - ``s_freeze``
     - Reset transition state; enters idle on the next cycle.

   * - ``s_idle``
     - No outstanding memory operation; can accept a new request.

   * - ``s_waiting``
     - A request has been issued and the LSU is waiting for a downstream
       response.

   * - ``s_drop``
     - A pipeline flush occurred; the LSU waits for the old response and drops
       it.

In ``s_idle``, ID/EX requests are accepted only when ``CoreBus.req.ready=1``.
In ``s_waiting``, independent new requests are not accepted.  If a response and
a new request handshake in the same cycle, the LSU stays in ``s_waiting`` and
transfers the outstanding slot to the new request.  In ``s_drop``, the LSU
raises ready to the downstream response channel and does not produce a valid WB
response.

Cache
-----

Caches are used only when ``EXPOSE_CORE_BUS=false``.  If ``HAS_ICACHE`` or
``HAS_DCACHE`` is disabled, the corresponding path is converted through
``CoreBus2CacheBus`` and uses single ``CacheBus`` transactions.

.. list-table:: Cache parameters
   :header-rows: 1

   * - Parameter
     - Current implementation

   * - Associativity
     - 2 ways.

   * - Banks
     - 4 banks.

   * - Bank data width
     - 32 bits.

   * - Cache line size
     - 16 bytes.

   * - Index width
     - ``FORMAL_CACHE_IDXW``, default ``7``.

   * - Number of sets
     - ``2^FORMAL_CACHE_IDXW``.

   * - Tag width
     - ``32 - FORMAL_CACHE_IDXW - 4``.

   * - Default capacity
     - ``2^7 * 2 ways * 16B = 4 KiB`` for each ICache and DCache.

Each cache way contains data SRAM and tag/valid SRAM.  Data SRAM is split into
32-bit banks.  DCache also has one dirty-bit RAM entry per way per set.
Replacement first chooses an invalid way.  If every way in the set is valid,
``LFSR8`` selects a random replacement way.  A 2-way LRU selector is present in
the source but is not enabled.

ICache
~~~~~~

``CL1ICACHE.scala`` receives ``CoreBus`` fetch requests from ``FetchAlign`` and
issues read requests on ``CacheBus``.  ICache handles instruction reads only.

.. list-table:: ICache states
   :header-rows: 1

   * - State
     - Description

   * - ``s_inval``
     - Clears tag-valid state by index after reset.

   * - ``s_idle``
     - Waits for a fetch request or invalidate request.

   * - ``s_lookup``
     - Reads tag/data arrays and checks for a hit.

   * - ``s_replace``
     - Issues a refill request after a miss.

   * - ``s_refill``
     - Receives refill data, writes data SRAM, and writes tag/valid on the last
       beat.

.. list-table:: ICache behavior
   :header-rows: 1

   * - Scenario
     - Behavior

   * - Cache hit
     - Returns the 32-bit bank selected by offset from the hit way.

   * - Cacheable miss
     - Issues a 4-beat burst read at the line-aligned address with ``len=3`` and
       ``size=2``.

   * - Refill
     - Can return the requested word as soon as the matching refill beat arrives
       and writes tag/valid on the final beat.

   * - Non-cacheable fetch
     - Performs a single read and does not fill the cache.

   * - ``FENCE.I`` invalidate
     - Clears all tag-valid bits by index and raises ``dxReq.ready`` when done.

ICache forwards refill response errors through ``CoreBus.rsp.err``.

DCache
~~~~~~

``CL1DCACHE.scala`` receives ``CoreBus`` requests from the LSU and issues
read/write requests on ``CacheBus``.  DCache is write-back and write-allocate.

.. list-table:: DCache states
   :header-rows: 1

   * - State
     - Description

   * - ``s_inval``
     - Clears tag-valid state.

   * - ``s_idle``
     - Waits for an LSU request or cache maintenance request.

   * - ``s_lookup``
     - Compares tags and handles hit read or hit write.

   * - ``s_miss``
     - Writes back a dirty line or issues a non-cacheable write.

   * - ``s_waitwrsp``
     - Waits for a write response.

   * - ``s_replace``
     - Issues a refill read request.

   * - ``s_refill``
     - Receives refill data and returns data for the active request.

   * - ``s_clean``
     - Scans and writes back dirty lines for clean maintenance.

.. list-table:: DCache access policy
   :header-rows: 1

   * - Scenario
     - Behavior

   * - Cacheable read hit
     - Returns the selected bank from the hit way.

   * - Cacheable write hit
     - Returns a write response, updates data SRAM with byte mask, and sets the
       dirty bit.

   * - Cacheable read miss, clean replacement
     - Directly refills the new line and clears dirty.

   * - Cacheable read miss, dirty replacement
     - Writes back the old line, waits for the write response, then refills.

   * - Cacheable write miss
     - Uses write allocate: refills the line, merges store data into the target
       beat, and marks the line dirty.

   * - Non-cacheable read
     - Performs a single read transaction and does not fill the cache.

   * - Non-cacheable write
     - Performs a single write transaction, waits for the write response, and
       does not update the cache.

DCache writeback and refill transfer 16-byte lines as 4-beat bursts.  The
writeback address is formed from the victim tag, current index, and line offset
zero.  The refill address is the line-aligned request address.  Both
writeback/refill use ``len=3``, ``size=2``, and ``mask=1111``.

Dirty-bit behavior is:

.. list-table:: Dirty-bit updates
   :header-rows: 1

   * - Event
     - Dirty behavior

   * - Write hit
     - Set dirty.

   * - Write miss refill completed
     - Set dirty.

   * - Read miss refill completed
     - Clear dirty.

   * - Clean completed
     - Clear dirty RAM.

To avoid a store-hit SRAM write colliding with a new load from the same
location, DCache uses ``rw_conflict`` to control cacheable read requests.

Cache Maintenance and FENCE.I
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``FENCE.I`` issues cache maintenance requests in ID/EX.

.. list-table:: Cache maintenance requests
   :header-rows: 1

   * - Object
     - Operation
     - Trigger

   * - ICache
     - Invalidate all tag-valid bits.
     - ``FENCE.I``.

   * - DCache
     - Clean dirty lines by scanning and writing back.
     - ``FENCE.I``.

ID/EX waits for both ICache invalidate and DCache clean to complete, then
reuses the branch flush path to redirect fetch so subsequent instructions are
refetched.  In CoreBus direct mode, or when the corresponding cache is not
instantiated, ``icache_req.ready`` and ``dcache_req.ready`` are tied true in
``CL1Core`` and ``FENCE.I`` reduces to a pipeline flush.

Idle Signals
~~~~~~~~~~~~

ICache and DCache export idle indicators used by the WFI halt flow.

.. list-table:: Cache idle signals
   :header-rows: 1

   * - Signal
     - Asserted when

   * - ``icache_idle``
     - The ICache main state machine is in ``s_idle``.

   * - ``dcache_idle``
     - The DCache main state machine is in ``s_idle`` and the post-hit-write
       write state machine is idle.

Bus Integration
---------------

The memory subsystem uses ready/valid style buses between fetch, LSU, caches,
and the external access path.  In CoreBus direct mode, instruction and data
paths are exposed as ``ibus`` and ``dbus``.  In cache/AXI mode, ICache and
DCache share the internal path and merge into the top-level AXI4 master
interface.  See :doc:`top_level_interfaces` for CoreBus fields, AXI channel
definitions, and current AXI implementation constraints.
