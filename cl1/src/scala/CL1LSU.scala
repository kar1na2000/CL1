// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._

import Control._
import cl1.Cl1Config._
import cl1.SimpleMask._

class LSU2WBSignal extends Bundle {
  val rdata = Output(UInt(32.W))
  val err   = Output(Bool())
}

object SignExt {
  def apply(sig: UInt, len: Int): UInt = {
    val signBit = sig(sig.getWidth - 1)
    if (sig.getWidth >= len) sig(len - 1, 0) else signBit.asUInt ## Fill(len - sig.getWidth, signBit) ## sig
  }
}

object ZeroExt {
  def apply(sig: UInt, len: Int): UInt = {
    if (sig.getWidth >= len) sig(len - 1, 0) else 0.U((len - sig.getWidth).W) ## sig
  }
}

//See https://github.com/OpenXiangShan/Utility/blob/master/src/main/scala/utility/LookupTree.scala

class CL1LSU extends Module {
  val io = IO(new Bundle {
    val in = new Bundle {
      val req = Flipped(Decoupled(new IDEX2LSUSignal()))
      val resp = Decoupled(new LSU2WBSignal())
      val flush = Input(Bool())
    }
    val out = new CoreBus()

    // TODO: Rename this status signal after its interface contract is finalized.
    val memNotOutStanding = Output(Bool())
  })

  // Track whether a request has been accepted by the external memory bus and is
  // still waiting for a response. At most one request may be outstanding; a new
  // request may be accepted in the same cycle that the previous response fires.
  //
  // req.fire rsp.fire outstanding_q outstanding_q_next
  //    0          0          0             0
  //    0          0          1             1
  //    0          1          0             x
  //    0          1          1             0
  //    1          0          0             1
  //    1          0          1             x
  //    1          1          0             x
  //    1          1          1             1
  //
  // x marks an illegal or unreachable combination under the single-outstanding
  // transaction contract.

  // TODO: Add assertions for the illegal combinations above.

  val outstanding_q = RegInit(false.B)
  when(io.out.req.fire) {
    outstanding_q := true.B
  }.elsewhen(io.out.rsp.fire) {
    outstanding_q := false.B
  }

  // Track whether the in-flight response should be drained instead of forwarded
  // to the core. This is set when a flush hits an outstanding request before its
  // response returns, and cleared once that response is consumed.
  //
  //  Assume outstanding_q is true:
  //  rsp.fire  io.in.flush     drop_q   drop_q_next
  //     0            0           0             0
  //     0            0           1             1
  //     0            1           0             1
  //     0            1           1             1
  //     1            0           0             0
  //     1            0           1             0
  //     1            1           0             0
  //     1            1           1             0

  // TODO: Assert that io.out.rsp.fire only occurs while outstanding_q is set.
  val drop_q = RegInit(false.B)
  when(outstanding_q && io.in.flush && !io.out.rsp.fire) {
    drop_q := true.B
  }.elsewhen(io.out.rsp.fire && drop_q) { // Only write drop_q when it changes.
    drop_q := false.B
  }



  // Handshake control. External bus handshakes define request and response
  // transaction boundaries.
  //
  // A new request may be accepted when no request is in flight, or when the
  // pending response is accepted in the same cycle.
  val req_ready_to_go = !outstanding_q || outstanding_q && io.out.rsp.fire
  io.in.req.ready := req_ready_to_go && io.out.req.ready
  io.out.req.valid := req_ready_to_go && io.in.req.valid

  // Forward responses only when they belong to non-flushed requests. Otherwise,
  // keep the external response channel ready so flushed responses are drained.
  val resp_can_go_to_core = !drop_q && !io.in.flush
  io.in.resp.valid := resp_can_go_to_core && io.out.rsp.valid
  io.out.rsp.ready := (resp_can_go_to_core && io.in.resp.ready) || !resp_can_go_to_core


  val addr = io.in.req.bits.addr
  io.out.req.bits.addr := addr

  // TODO: Refactor the memory-operation decoder and encoding.
  val wen = io.in.req.bits.memType(3)
  val size_raw = io.in.req.bits.memType(2, 1)
  val sign = io.in.req.bits.memType(0)

  // Decode the access size and address offset into a byte-lane mask.
  // TODO: Assert that mask_raw is one of the legal aligned access encodings.
  val mask_raw = io.in.req.bits.addr(1, 0) ## size_raw
  val mask = Mux1H(Seq(
    (mask_raw === "b0001".U) -> MASK_B0,
    (mask_raw === "b0101".U) -> MASK_B1,
    (mask_raw === "b1001".U) -> MASK_B2,
    (mask_raw === "b1101".U) -> MASK_B3,
    (mask_raw === "b0010".U) -> MASK_LO,
    (mask_raw === "b1010".U) -> MASK_HI,
    (mask_raw === "b0011".U) -> MASK_ALL
  ))
  io.out.req.bits.mask := mask

  // Align write data to the byte lanes selected by the request mask.
  val one_byte_wdata = io.in.req.bits.wdata(7, 0)
  val two_bytes_wdata = io.in.req.bits.wdata(15, 0)
  val wdata = Mux1H(Seq(
    (mask_raw === "b0001".U) -> io.in.req.bits.wdata,
    (mask_raw === "b0101".U) -> (0.U(16.W) ## one_byte_wdata ## 0.U(8.W)),
    (mask_raw === "b1001".U) -> (0.U(8.W) ## one_byte_wdata ## 0.U(16.W)),
    (mask_raw === "b1101".U) -> (one_byte_wdata ## 0.U(24.W)),
    (mask_raw === "b0010".U) -> io.in.req.bits.wdata,
    (mask_raw === "b1010".U) -> (two_bytes_wdata ## 0.U(16.W)),
    (mask_raw === "b0011".U) -> io.in.req.bits.wdata
  ))
  // Drive the aligned write data onto the external bus.
  io.out.req.bits.data := wdata

  io.out.req.bits.wen := wen
  // TODO: Rename this field if the cacheability contract changes.
  io.out.req.bits.cache := MemoryMap.isDCacheable(addr)

  // Translate the LSU size encoding into the CoreBus size encoding.
  io.out.req.bits.size := MuxLookup(size_raw, 0.U)(Seq(
    1.U -> 0.U,
    2.U -> 1.U,
    3.U -> 2.U
  ))

  // Latch request attributes so the response can be decoded with the matching
  // access type and byte lanes.
  val req_type_buf_q = RegInit(0.U(MEM_WIDTH.W))
  // TODO: Replace the literal mask width with a named constant.
  val req_mask_buf_q = RegInit(0.U(4.W))
  when(io.out.req.fire) {
    req_type_buf_q := io.in.req.bits.memType
    req_mask_buf_q := io.out.req.bits.mask
  }

  val rdata_raw = io.out.rsp.bits.data

  // For byte loads, the saved byte-lane mask is one-hot and selects the
  // addressed byte from the returned word.
  val one_byte_rdata = Mux1H(Seq(
    req_mask_buf_q(0) -> rdata_raw(7, 0),
    req_mask_buf_q(1) -> rdata_raw(15, 8),
    req_mask_buf_q(2) -> rdata_raw(23, 16),
    req_mask_buf_q(3) -> rdata_raw(31, 24),
  ))

  // Halfword loads use either the low or high half of the returned word.
  val two_bytes_rdata = Mux(req_mask_buf_q(0), rdata_raw(15, 0), rdata_raw(31, 16))

  // TODO: Replace these literal memType encodings with named constants.
  val rdata = Mux1H(Seq(
    (req_type_buf_q(2, 0) === "b010".U) -> SignExt(one_byte_rdata, 32),
    (req_type_buf_q(2, 0) === "b011".U) -> ZeroExt(one_byte_rdata, 32),
    (req_type_buf_q(2, 0) === "b100".U) -> SignExt(two_bytes_rdata, 32),
    (req_type_buf_q(2, 0) === "b101".U) -> ZeroExt(two_bytes_rdata, 32),
    (req_type_buf_q(2, 0) === "b110".U) -> rdata_raw

  ))

  io.in.resp.bits.rdata := rdata

  // How to deal with store fault in other implementations ?
  io.in.resp.bits.err := io.out.rsp.bits.err

  // Expose whether the LSU can accept another memory transaction.
  io.memNotOutStanding := req_ready_to_go

  val lsu_ck_en = outstanding_q || io.in.req.valid

}
