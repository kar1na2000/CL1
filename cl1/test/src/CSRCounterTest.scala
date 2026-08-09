package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class CL1CSRCounterHarness extends Module {
  val io = IO(new Bundle {
    val rdAddr = Input(UInt(12.W))
    val rdValue = Output(UInt(32.W))
    val wrAddr = Input(UInt(12.W))
    val wrValue = Input(UInt(32.W))
    val wen = Input(Bool())
    val wb_retire = Input(Bool())
  })

  val csr = Module(new CL1CSR())
  csr.io.always_on_clock := clock
  csr.io.rdAddr := io.rdAddr
  io.rdValue := csr.io.rdValue
  csr.io.wrAddr := io.wrAddr
  csr.io.wrValue := io.wrValue
  csr.io.wen := io.wen
  csr.io.wb_retire := io.wb_retire
  csr.io.instr := 0.U
  csr.io.c_instr := 0.U
  csr.io.ext_irq := false.B
  csr.io.sft_irq := false.B
  csr.io.tmr_irq := false.B

  csr.io.dbg_intf.csr_update_en := false.B
  csr.io.dbg_intf.dpc_update := 0.U
  csr.io.dbg_intf.dbg_cause := 0.U

  csr.io.excp_intf.cmt_epc_en := false.B
  csr.io.excp_intf.cmt_epc_n := 0.U
  csr.io.excp_intf.cmt_status_en := false.B
  csr.io.excp_intf.cmt_cause_en := false.B
  csr.io.excp_intf.cmt_cause_n := 0.U
  csr.io.excp_intf.cmt_tval_en := false.B
  csr.io.excp_intf.cmt_tval_n := 0.U
  csr.io.excp_intf.cmt_mret_en := false.B
}

class CSRCounterTest extends AnyFreeSpec with ChiselScalatestTester {
  private val WordMask = (BigInt(1) << 32) - 1

  private def u32(value: BigInt): UInt = (value & WordMask).U(32.W)

  private def pokeDefaults(dut: CL1CSRCounterHarness): Unit = {
    dut.io.rdAddr.poke(0.U)
    dut.io.wrAddr.poke(0.U)
    dut.io.wrValue.poke(0.U)
    dut.io.wen.poke(false.B)
    dut.io.wb_retire.poke(false.B)
  }

  private def resetDut(dut: CL1CSRCounterHarness): Unit = {
    pokeDefaults(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def expectCSR(dut: CL1CSRCounterHarness, addr: UInt, value: BigInt): Unit = {
    dut.io.rdAddr.poke(addr)
    dut.io.rdValue.expect(u32(value))
  }

  private def writeCSR(dut: CL1CSRCounterHarness, addr: UInt, value: BigInt, retire: Boolean = false): Unit = {
    dut.io.wrAddr.poke(addr)
    dut.io.wrValue.poke(u32(value))
    dut.io.wen.poke(true.B)
    dut.io.wb_retire.poke(if (retire) true.B else false.B)
    dut.clock.step()
    dut.io.wen.poke(false.B)
    dut.io.wb_retire.poke(false.B)
    dut.io.wrAddr.poke(0.U)
    dut.io.wrValue.poke(0.U)
  }

  "mcycle and mcycleh increment and carry across CSR writes" in {
    test(new CL1CSRCounterHarness()) { dut =>
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

  "minstret and minstreth increment only on retirement and carry cleanly" in {
    test(new CL1CSRCounterHarness()) { dut =>
      resetDut(dut)

      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 0)

      dut.clock.step(2)
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 0)

      writeCSR(dut, CSRs.mscratch, 0, retire = true)
      expectCSR(dut, CSRs.minstret, 1)
      expectCSR(dut, CSRs.minstreth, 0)

      writeCSR(dut, CSRs.minstreth, 3)
      writeCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstreth, 3)

      dut.clock.step(2)
      expectCSR(dut, CSRs.minstret, WordMask)
      expectCSR(dut, CSRs.minstreth, 3)

      dut.io.wb_retire.poke(true.B)
      dut.clock.step()
      dut.io.wb_retire.poke(false.B)
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 4)

      dut.clock.step()
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 4)

      writeCSR(dut, CSRs.minstreth, 7)
      writeCSR(dut, CSRs.minstret, WordMask)
      writeCSR(dut, CSRs.minstret, 0x1234, retire = true)
      expectCSR(dut, CSRs.minstret, 0x1234)
      expectCSR(dut, CSRs.minstreth, 7)

      writeCSR(dut, CSRs.minstreth, 9, retire = true)
      expectCSR(dut, CSRs.minstret, 0x1235)
      expectCSR(dut, CSRs.minstreth, 9)

      writeCSR(dut, CSRs.minstret, WordMask)
      writeCSR(dut, CSRs.minstreth, 0xa, retire = true)
      expectCSR(dut, CSRs.minstret, 0)
      expectCSR(dut, CSRs.minstreth, 0xa)
    }
  }
}
