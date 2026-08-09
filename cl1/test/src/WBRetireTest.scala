package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class WBRetireTest extends AnyFreeSpec with ChiselScalatestTester {
  private object Mem {
    val LW = 0x6
    val SW = 0xe
  }

  private def pokeDefaults(dut: CL1WBStage): Unit = {
    dut.io.pplIn.valid.poke(false.B)
    dut.io.pplIn.bits.wbType.poke(0.U)
    dut.io.pplIn.bits.rdWdat.poke(0.U)
    dut.io.pplIn.bits.csrWdat.poke(0.U)
    dut.io.pplIn.bits.wen.poke(false.B)
    dut.io.pplIn.bits.csrWen.poke(false.B)
    dut.io.pplIn.bits.memType.poke(0.U)
    dut.io.pplIn.bits.pc.poke("h80000000".U)
    dut.io.pplIn.bits.privInstr.poke(0.U)
    dut.io.pplIn.bits.inst.poke("h00000013".U)
    dut.io.pplIn.bits.cInst.poke(0.U)
    dut.io.pplIn.bits.isCInst.poke(false.B)
    dut.io.pplIn.bits.isTrap.poke(false.B)
    dut.io.pplIn.bits.trapCode.poke(0.U)
    dut.io.pplIn.bits.trapValue.poke(0.U)
    dut.io.pplIn.bits.dx_ready.poke(true.B)

    dut.io.mem.valid.poke(false.B)
    dut.io.mem.bits.rdata.poke(0.U)
    dut.io.mem.bits.err.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def driveInstruction(
      dut: CL1WBStage,
      privInstr: Int = 0,
      isTrap: Boolean = false,
      trapCode: Int = 0): Unit = {
    dut.io.pplIn.valid.poke(true.B)
    dut.io.pplIn.bits.privInstr.poke(privInstr.U)
    dut.io.pplIn.bits.isTrap.poke(isTrap.B)
    dut.io.pplIn.bits.trapCode.poke(trapCode.U)
  }

  "WB retires normal instructions and legal privileged instructions" in {
    test(new CL1WBStage()) { dut =>
      pokeDefaults(dut)

      Seq(
        "normal instruction" -> 0,
        "MRET" -> (1 << 1),
        "WFI" -> (1 << 4)
      ).foreach { case (name, privInstr) =>
        driveInstruction(dut, privInstr = privInstr)
        dut.io.commit.expect(true.B, name)
        dut.io.retire.expect(true.B, name)
      }
    }
  }

  "WB does not retire instructions that raise synchronous exceptions" in {
    test(new CL1WBStage()) { dut =>
      pokeDefaults(dut)

      Seq(
        ("instruction access fault", 1, 0),
        ("illegal instruction", 2, 0),
        ("illegal MRET", 2, 1 << 1),
        ("illegal WFI", 2, 1 << 4),
        ("load address misaligned", 4, 0),
        ("store address misaligned", 6, 0),
        ("U-mode ECALL", 8, 1 << 3),
        ("M-mode ECALL", 11, 1 << 3)
      ).foreach { case (name, trapCode, privInstr) =>
        driveInstruction(dut, privInstr = privInstr, isTrap = true, trapCode = trapCode)
        dut.io.commit.expect(true.B, name)
        dut.io.retire.expect(false.B, name)
        dut.io.toExcp.excp_valid.expect(true.B, name)
        dut.io.toExcp.excp_code.expect(trapCode.U, name)
      }
    }
  }

  "WB does not retire load or store access faults" in {
    test(new CL1WBStage()) { dut =>
      pokeDefaults(dut)
      driveInstruction(dut)
      dut.io.pplIn.bits.memType.poke(Mem.LW.U)
      dut.io.commit.expect(false.B, "load waiting for response")
      dut.io.retire.expect(false.B, "load waiting for response")

      dut.io.mem.valid.poke(true.B)
      dut.io.mem.bits.err.poke(true.B)

      Seq(
        ("load access fault", Mem.LW, 5),
        ("store access fault", Mem.SW, 7)
      ).foreach { case (name, memType, trapCode) =>
        driveInstruction(dut)
        dut.io.pplIn.bits.memType.poke(memType.U)
        dut.io.commit.expect(true.B, name)
        dut.io.retire.expect(false.B, name)
        dut.io.toExcp.excp_valid.expect(true.B, name)
        dut.io.toExcp.excp_code.expect(trapCode.U, name)
      }

      dut.io.mem.bits.err.poke(false.B)
      Seq(
        "successful load" -> Mem.LW,
        "successful store" -> Mem.SW
      ).foreach { case (name, memType) =>
        dut.io.pplIn.bits.memType.poke(memType.U)
        dut.io.commit.expect(true.B, name)
        dut.io.retire.expect(true.B, name)
        dut.io.toExcp.excp_valid.expect(false.B, name)
      }
    }
  }

  "WB does not retire EBREAK or flushed instructions" in {
    test(new CL1WBStage()) { dut =>
      pokeDefaults(dut)
      driveInstruction(dut, privInstr = 1 << 2)
      dut.io.commit.expect(true.B)
      dut.io.retire.expect(false.B)

      dut.io.pplIn.bits.privInstr.poke(0.U)
      dut.io.flush.poke(true.B)
      dut.io.commit.expect(false.B)
      dut.io.retire.expect(false.B)
    }
  }
}
