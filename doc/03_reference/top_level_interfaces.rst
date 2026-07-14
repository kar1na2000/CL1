Top-Level Bus Interfaces
========================

The CL1 top level is composed of fixed control signals plus one configurable
external memory interface.  The memory interface is selected by
``EXPOSE_CORE_BUS``:

* ``EXPOSE_CORE_BUS=true`` exposes separate native ``CoreBus`` ports for
  instruction and data access.
* ``EXPOSE_CORE_BUS=false`` exposes one AXI4 master interface.

Fixed I/O
---------

The fixed I/O signals are present across the generated top-level variants:

``clock``
   Input, 1 bit. Top-level clock.

``reset``
   Input, 1 bit. Top-level reset.  The core can adapt the active level and
   synchronous or asynchronous style through configuration.

``ext_irq``
   Input, 1 bit. Machine external interrupt input.

``sft_irq``
   Input, 1 bit. Machine software interrupt input.

``tmr_irq``
   Input, 1 bit. Machine timer interrupt input.

``dbg_req_i``
   Input, 1 bit. External debug request input.

``boot_addr``
   Input, 32 bits. Reset fetch address supplied by the integration environment.

``diff_o``
   Output bundle. Optional differential-test commit interface, generated when
   ``SOC_DIFF=true``.

``rvfi``
   Flat I/O bundle. Optional RVFI interface, generated when
   ``FORMAL_VERIF=true``.

Native CoreBus Interface
------------------------

In ``EXPOSE_CORE_BUS=true`` configurations, the top level exposes two native
``CoreBus`` ports:

* ``ibus`` for instruction fetch traffic.
* ``dbus`` for load/store traffic.

Both ports use Decoupled ready/valid handshakes.  Each port has a request
channel named ``req`` and a response channel named ``rsp``.

.. list-table:: CoreBus request channel
   :header-rows: 1

   * - Field
     - Direction
     - Width
     - Description

   * - ``req.valid``
     - master to slave
     - 1
     - Request is valid.

   * - ``req.ready``
     - slave to master
     - 1
     - Request can be accepted.

   * - ``req.bits.addr``
     - master to slave
     - 32
     - Byte address.

   * - ``req.bits.data``
     - master to slave
     - 32
     - Write data.

   * - ``req.bits.wen``
     - master to slave
     - 1
     - Write enable.  ``1`` selects a write, ``0`` selects a read.

   * - ``req.bits.mask``
     - master to slave
     - 4
     - Byte write mask.

   * - ``req.bits.cache``
     - master to slave
     - 1
     - Cacheable attribute.

   * - ``req.bits.size``
     - master to slave
     - 2
     - Access size: ``0`` for 1 byte, ``1`` for 2 bytes, ``2`` for 4 bytes.

.. list-table:: CoreBus response channel
   :header-rows: 1

   * - Field
     - Direction
     - Width
     - Description

   * - ``rsp.valid``
     - slave to master
     - 1
     - Response is valid.

   * - ``rsp.ready``
     - master to slave
     - 1
     - Response can be accepted.

   * - ``rsp.bits.data``
     - slave to master
     - 32
     - Read data.

   * - ``rsp.bits.err``
     - slave to master
     - 1
     - Error response.

AXI4 Master Interface
---------------------

When ``EXPOSE_CORE_BUS=false``, CL1 exposes an AXI4 memory-mapped master
interface.  The interface follows the Arm AMBA AXI4 ready/valid handshake
model described by the `AMBA AXI and ACE Protocol Specification
<https://developer.arm.com/documentation/ihi0022/e>`__.

The default address width is 32 bits, the default data width is 32 bits, and
the top-level transaction ID width is 5 bits.  When ``SOC_D64=true``, the top
level inserts an AXI width converter so the exported data bus is 64 bits wide.

The AXI channels observe the standard VALID/READY rules:

* A transfer occurs only when VALID and READY are both high in the same cycle.
* The master does not wait for READY before asserting AW, W, or AR VALID.
* Once VALID is asserted, VALID and payload signals remain stable until the
  channel handshakes.
* AW and W are independent channels.  They may handshake in the same cycle or
  in different cycles.
* If one channel handshakes before its partner channel, the pending state is
  held until the other channel also completes.

The current implementation uses AXI4 mainly for CL1 instruction fetch and
load/store traffic.  ``awburst`` and ``arburst`` are fixed to ``INCR``.
``awlock``/``arlock``, ``awcache``/``arcache``, and
``awprot``/``arprot`` are driven with fixed values.  ID signals keep the
configured width, but the design does not rely on multiple outstanding IDs for
out-of-order completion.

.. list-table:: AXI4 write address channel
   :header-rows: 1

   * - Field
     - Width
     - Description

   * - ``aw.valid`` / ``aw.ready``
     - 1
     - Write address handshake.

   * - ``awaddr``
     - 32
     - Write address.

   * - ``awid``
     - ``ID_WIDTH``
     - Fixed to zero by the current requester.

   * - ``awlen``
     - 8
     - Burst length minus one.

   * - ``awsize``
     - 3
     - Beat size.

   * - ``awburst``
     - 2
     - Fixed to ``INCR``.

   * - ``awlock``
     - 1
     - Fixed to zero.

   * - ``awcache``
     - 4
     - Fixed to zero.

   * - ``awprot``
     - 3
     - Fixed to zero.

.. list-table:: AXI4 write data and response channels
   :header-rows: 1

   * - Field
     - Width
     - Description

   * - ``w.valid`` / ``w.ready``
     - 1
     - Write data handshake.

   * - ``wdata``
     - ``DATA_WIDTH``
     - Write data.

   * - ``wstrb``
     - ``DATA_WIDTH / 8``
     - Byte strobes.

   * - ``wlast``
     - 1
     - Last beat of a burst.

   * - ``b.valid`` / ``b.ready``
     - 1
     - Write response handshake.

   * - ``bresp``
     - 2
     - Write response code.

   * - ``bid``
     - ``ID_WIDTH``
     - Write response ID.

.. list-table:: AXI4 read address and response channels
   :header-rows: 1

   * - Field
     - Width
     - Description

   * - ``ar.valid`` / ``ar.ready``
     - 1
     - Read address handshake.

   * - ``araddr``
     - 32
     - Read address.

   * - ``arid``
     - ``ID_WIDTH``
     - Fixed to zero by the current requester.

   * - ``arlen``
     - 8
     - Burst length minus one.

   * - ``arsize``
     - 3
     - Beat size.

   * - ``arburst``
     - 2
     - Fixed to ``INCR``.

   * - ``arlock``
     - 1
     - Fixed to zero.

   * - ``arcache``
     - 4
     - Fixed to zero.

   * - ``arprot``
     - 3
     - Fixed to zero.

   * - ``r.valid`` / ``r.ready``
     - 1
     - Read data handshake.

   * - ``rdata``
     - ``DATA_WIDTH``
     - Read data.

   * - ``rresp``
     - 2
     - Read response code.

   * - ``rlast``
     - 1
     - Last beat of a burst.

   * - ``rid``
     - ``ID_WIDTH``
     - Read response ID.

.. list-table:: Current AXI4 implementation constraints
   :header-rows: 1

   * - Field
     - Current implementation
     - Description

   * - ``awid`` / ``arid``
     - ``0``
     - Multiple-ID concurrency and out-of-order completion are not used.

   * - ``awburst`` / ``arburst``
     - ``INCR``
     - Only incrementing bursts are generated.

   * - ``awlock`` / ``arlock``
     - ``0``
     - Locked or exclusive transactions are not generated.

   * - ``awcache`` / ``arcache``
     - ``0``
     - Internal cache attributes are not mapped to AxCACHE.

   * - ``awprot`` / ``arprot``
     - ``0``
     - Privileged, secure, and instruction attributes are not distinguished.
