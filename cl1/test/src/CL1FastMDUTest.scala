package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class CL1FastMDUTest extends AnyFreeSpec with ChiselScalatestTester {
  private def initialize(dut: CL1FastMDU): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.rs1.poke(0.U)
    dut.io.in.bits.rs2.poke(0.U)
    dut.io.in.bits.op.poke(0.U)
    dut.io.in.bits.is_div.poke(false.B)
    dut.io.in.bits.flush.poke(false.B)
    dut.io.in.bits.b2b.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.alu_req.rslt.poke(0.U)
  }

  private def expectMul(
      dut: CL1FastMDU,
      rs1: BigInt,
      rs2: BigInt,
      op: Int,
      expected: BigInt
  ): Unit = {
    dut.io.in.bits.rs1.poke(rs1.U(32.W))
    dut.io.in.bits.rs2.poke(rs2.U(32.W))
    dut.io.in.bits.op.poke(op.U)
    dut.io.in.bits.is_div.poke(false.B)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.io.out.valid.expect(true.B)
    dut.io.out.bits.expect(expected.U(32.W))
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def expectDiv(
      dut: CL1FastMDU,
      rs1: BigInt,
      rs2: BigInt,
      op: Int,
      expected: BigInt
  ): Unit = {
    dut.io.in.bits.rs1.poke(rs1.U(32.W))
    dut.io.in.bits.rs2.poke(rs2.U(32.W))
    dut.io.in.bits.op.poke(op.U)
    dut.io.in.bits.is_div.poke(true.B)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)

    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 40) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 40, "divider response timed out")
    dut.io.out.bits.expect(expected.U(32.W))
    dut.clock.step()
  }

  "produce all multiply variants in the request cycle" in {
    test(new CL1FastMDU()) { dut =>
      initialize(dut)
      // Both compatibility fields are deliberately ignored by this MDU.
      dut.io.in.bits.flush.poke(true.B)
      dut.io.in.bits.b2b.poke(true.B)

      expectMul(dut, BigInt("fffffffd", 16), 7, 1, BigInt("ffffffeb", 16))
      expectMul(dut, BigInt("fffffffd", 16), 7, 2, BigInt("ffffffff", 16))
      expectMul(dut, BigInt("fffffffd", 16), 7, 4, BigInt("ffffffff", 16))
      expectMul(dut, BigInt("fffffffd", 16), 7, 8, 6)
    }
  }

  "hold a multiply request until the output is ready" in {
    test(new CL1FastMDU()) { dut =>
      initialize(dut)
      dut.io.in.bits.rs1.poke(6.U)
      dut.io.in.bits.rs2.poke(7.U)
      dut.io.in.bits.op.poke(1.U)
      dut.io.in.bits.is_div.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(false.B)

      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(42.U)
      dut.clock.step(2)
      dut.io.out.bits.expect(42.U)

      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
    }
  }

  "execute signed and unsigned divide and remainder operations" in {
    test(new CL1FastMDU()) { dut =>
      initialize(dut)

      expectDiv(dut, BigInt("fffffff9", 16), 3, 1, BigInt("fffffffe", 16))
      expectDiv(dut, BigInt("fffffff9", 16), 3, 2, BigInt("ffffffff", 16))
      expectDiv(dut, BigInt("fffffff9", 16), 3, 4, BigInt("55555553", 16))
      expectDiv(dut, BigInt("fffffff9", 16), 3, 8, 0)
      expectDiv(dut, 123, 0, 1, BigInt("ffffffff", 16))
      expectDiv(dut, 123, 0, 2, 123)
    }
  }

  "retain a divider response across output backpressure" in {
    test(new CL1FastMDU()) { dut =>
      initialize(dut)
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.rs1.poke(100.U)
      dut.io.in.bits.rs2.poke(7.U)
      dut.io.in.bits.op.poke(4.U)
      dut.io.in.bits.is_div.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 40, "divider response timed out under backpressure")
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(14.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  "ignore flush and b2b for divider requests" in {
    test(new CL1FastMDU()) { dut =>
      initialize(dut)
      dut.io.in.bits.flush.poke(true.B)
      dut.io.in.bits.b2b.poke(true.B)
      expectDiv(dut, 100, 7, 4, 14)
    }
  }
}
