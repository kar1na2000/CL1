// SPDX-License-Identifier: MulanPSL-2.0

package cl1

import chisel3._
import chisel3.util._
import cl1.CL1Config.RISCV_FORMAL_ALTOPS

/** RV32M unit with a single-cycle combinational multiplier.
  *
  * Multiplication requests use a combinational Decoupled path: a result is
  * valid in the same cycle as the request and is accepted when both sides are
  * ready. Division requests are executed by the existing RestoringDivider.
  * The MDUIOLp flush and b2b fields are retained only for interface
  * compatibility and are not used by either datapath.
  */
class CL1FastMDU extends CL1MDUBase {
  val mduRs1   = io.in.bits.rs1
  val mduRs2   = io.in.bits.rs2
  val mduOp    = io.in.bits.op
  val mduIsDiv = io.in.bits.is_div

  val mulOp    = mduOp(0)
  val mulhOp   = mduOp(1)
  val mulhsuOp = mduOp(2)
  val mulhuOp  = mduOp(3)

  val divOp  = mduOp(0)
  val remOp  = mduOp(1)
  val divuOp = mduOp(2)
  val remuOp = mduOp(3)

  // Extending both operands to 33 bits lets a single signed multiplication
  // cover signed x signed, signed x unsigned, and unsigned x unsigned.
  val mulRs1 = Mux(mulhuOp, Cat(0.U(1.W), mduRs1), Cat(mduRs1(31), mduRs1))
  val mulRs2 = Mux(mulhuOp || mulhsuOp, Cat(0.U(1.W), mduRs2), Cat(mduRs2(31), mduRs2))
  val product = (mulRs1.asSInt * mulRs2.asSInt).asUInt
  val mulResult = Mux(mulOp, product(31, 0), product(63, 32))

  // Preserve riscv-formal's optional transformed M-extension semantics when
  // this implementation is selected for an RVFI build.
  val altopsBitmask = Mux1H(Seq(
    (!mduIsDiv && mulOp)    -> "h5876063e".U,
    (!mduIsDiv && mulhOp)   -> "hf6583fb7".U,
    (!mduIsDiv && mulhsuOp) -> "hecfbe137".U,
    (!mduIsDiv && mulhuOp)  -> "h949ce5e8".U,
    (mduIsDiv && divOp)     -> "h7f8529ec".U,
    (mduIsDiv && divuOp)    -> "h10e8fd70".U,
    (mduIsDiv && remOp)     -> "h8da68fa5".U,
    (mduIsDiv && remuOp)    -> "h3138d0e1".U
  ))
  val altopsIsSub = mduIsDiv || (!mduIsDiv && mulhsuOp)
  val altopsBase  = Mux(altopsIsSub, mduRs1 - mduRs2, mduRs1 + mduRs2)
  val altopsResult = altopsBase ^ altopsBitmask
  val selectedMulResult = if (RISCV_FORMAL_ALTOPS) altopsResult else mulResult

  val divider = Module(new RestoringDivider(32))
  val divActive = RegInit(false.B)
  val divSelectRemainder = RegEnable(remOp || remuOp, false.B, divider.io.in.fire)
  val divAltopsResult = RegEnable(altopsResult, 0.U(32.W), divider.io.in.fire)

  val divPendingValid = RegInit(false.B)
  val divPendingBits  = Reg(UInt(32.W))
  val unitIdle        = !divActive && !divPendingValid

  divider.io.in.bits(0) := mduRs1
  divider.io.in.bits(1) := mduRs2
  divider.io.in.valid := io.in.valid && mduIsDiv && unitIdle
  divider.io.sign := divOp || remOp
  // RestoringDivider emits a one-cycle response. The wrapper below either
  // consumes it directly or stores it until the downstream becomes ready.
  divider.io.out.ready := true.B

  val dividerResult = Mux(
    divSelectRemainder,
    divider.io.out.bits(63, 32),
    divider.io.out.bits(31, 0)
  )
  val selectedDividerResult = if (RISCV_FORMAL_ALTOPS) divAltopsResult else dividerResult
  val dividerResponseValid = divider.io.out.valid

  when(divider.io.in.fire) {
    divActive := true.B
  }.elsewhen(divider.io.out.valid) {
    divActive := false.B
  }

  when(divPendingValid && io.out.ready) {
    divPendingValid := false.B
  }.elsewhen(dividerResponseValid && !io.out.ready) {
    divPendingValid := true.B
    divPendingBits  := selectedDividerResult
  }

  val mulResponseValid = io.in.valid && !mduIsDiv && unitIdle
  val divResponseValid = divPendingValid || dividerResponseValid

  io.out.valid := mulResponseValid || divResponseValid
  io.out.bits := Mux(
    divPendingValid,
    divPendingBits,
    Mux(dividerResponseValid, selectedDividerResult, selectedMulResult)
  )

  // A multiplication request is accepted only with its combinational result.
  // Division acceptance is governed by the iterative divider's input channel.
  io.in.ready := unitIdle && Mux(mduIsDiv, divider.io.in.ready, io.out.ready)

  // The fast MDU owns its arithmetic datapaths, so the compatibility ALU port
  // remains inactive regardless of the MDU_SHAERALU setting.
  io.alu_req.req := false.B
  io.alu_req.op1 := 0.U
  io.alu_req.op2 := 0.U
  io.alu_req.sub := false.B

  // Wake for a new request and stay awake while a division/result is pending.
  mdu_ck_en := io.in.valid || divActive || divPendingValid
}
