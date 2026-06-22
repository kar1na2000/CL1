package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class CSRCounterTest extends AnyFreeSpec with ChiselScalatestTester {
  private val WordMask = (BigInt(1) << 32) - 1

  private def u32(value: BigInt): UInt = (value & WordMask).U(32.W)

  private def pokeDefaults(dut: Cl1CSR): Unit = {
    dut.io.rdAddr.poke(0.U)
    dut.io.wrAddr.poke(0.U)
    dut.io.wrValue.poke(0.U)
    dut.io.wen.poke(false.B)
    dut.io.instr.poke(0.U)
    dut.io.c_instr.poke(0.U)
    dut.io.wb_commit.poke(false.B)

    dut.io.dbg_intf.csr_update_en.poke(false.B)
    dut.io.dbg_intf.dpc_update.poke(0.U)
    dut.io.dbg_intf.dbg_cause.poke(0.U)

    dut.io.excp_intf.ext_irq.poke(false.B)
    dut.io.excp_intf.sft_irq.poke(false.B)
    dut.io.excp_intf.tmr_irq.poke(false.B)
    dut.io.excp_intf.cmt_epc_en.poke(false.B)
    dut.io.excp_intf.cmt_epc_n.poke(0.U)
    dut.io.excp_intf.cmt_status_en.poke(false.B)
    dut.io.excp_intf.cmt_cause_en.poke(false.B)
    dut.io.excp_intf.cmt_cause_n.poke(0.U)
    dut.io.excp_intf.cmt_tval_en.poke(false.B)
    dut.io.excp_intf.cmt_tval_n.poke(0.U)
    dut.io.excp_intf.cmt_mret_en.poke(false.B)
  }

  private def resetDut(dut: Cl1CSR): Unit = {
    pokeDefaults(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def expectCSR(dut: Cl1CSR, addr: UInt, value: BigInt): Unit = {
    dut.io.rdAddr.poke(addr)
    dut.io.rdValue.expect(u32(value))
  }

  private def writeCSR(dut: Cl1CSR, addr: UInt, value: BigInt, commit: Boolean = false): Unit = {
    dut.io.wrAddr.poke(addr)
    dut.io.wrValue.poke(u32(value))
    dut.io.wen.poke(true.B)
    dut.io.wb_commit.poke(if (commit) true.B else false.B)
    dut.clock.step()
    dut.io.wen.poke(false.B)
    dut.io.wb_commit.poke(false.B)
    dut.io.wrAddr.poke(0.U)
    dut.io.wrValue.poke(0.U)
  }

  "mcycle and mcycleh increment and carry across CSR writes" in {
    test(new Cl1CSR()) { dut =>
      resetDut(dut)

      expectCSR(dut, CSRs.mcycle, 0)
      expectCSR(dut, CSRs.mcycleh, 0)

      dut.clock.step()
      expectCSR(dut, CSRs.mcycle, 1)
      expectCSR(dut, CSRs.mcycleh, 0)

      writeCSR(dut, CSRs.mcycleh, 5)
      writeCSR(dut, CSRs.mcycle, WordMask)
      expectCSR(dut, CSRs.mcycle, WordMask)
      expectCSR(dut, CSRs.mcycleh, 5)

      writeCSR(dut, CSRs.mcycleh, 0x22)
      expectCSR(dut, CSRs.mcycle, 0)
      expectCSR(dut, CSRs.mcycleh, 0x22)

      writeCSR(dut, CSRs.mcycle, WordMask)
      expectCSR(dut, CSRs.mcycle, WordMask)
      expectCSR(dut, CSRs.mcycleh, 0x22)

      dut.clock.step()
      expectCSR(dut, CSRs.mcycle, 0)
      expectCSR(dut, CSRs.mcycleh, 0x23)
    }
  }

  "minstret and minstreth increment only on commit and carry cleanly" in {
    test(new Cl1CSR()) { dut =>
      resetDut(dut)

      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 0)

      dut.clock.step(2)
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 0)

      writeCSR(dut, CSRs.minstreth, 3)
      writeCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstreth, 3)

      dut.clock.step(2)
      expectCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstreth, 3)

      dut.io.wb_commit.poke(true.B)
      dut.clock.step()
      dut.io.wb_commit.poke(false.B)
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 4)

      dut.clock.step()
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 4)

      writeCSR(dut, CSRs.minstreth, 7)
      writeCSR(dut, CSRs.minstret, WordMask)
      writeCSR(dut, CSRs.minstret, 0x1234, commit = true)
      expectCSR(dut, CSRs.minstret, 0x1234)
      expectCSR(dut, CSRs.minstreth, 7)
    }
  }
}
