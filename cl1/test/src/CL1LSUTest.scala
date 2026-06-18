package cl1

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class CL1LSUTest extends AnyFreeSpec with ChiselScalatestTester {
  private object Mem {
    val SB  = 0xa
    val SH  = 0xc
    val SW  = 0xe
    val LB  = 0x2
    val LBU = 0x3
    val LH  = 0x4
    val LHU = 0x5
    val LW  = 0x6
  }

  private case class ReqCase(
      name: String,
      memType: Int,
      addr: BigInt,
      wdata: BigInt,
      mask: Int,
      size: Int,
      wen: Boolean,
      cache: Boolean,
      checkStoreData: Boolean = false)

  private case class LoadCase(
      name: String,
      memType: Int,
      addr: BigInt,
      rspData: BigInt,
      expectedRdata: BigInt)

  private val cachedBase = BigInt("80000000", 16)
  private val mmioBase   = BigInt("10000000", 16)

  private def init(dut: CL1LSU): Unit = {
    dut.io.in.req.valid.poke(false.B)
    dut.io.in.req.bits.memType.poke(0.U)
    dut.io.in.req.bits.addr.poke(0.U)
    dut.io.in.req.bits.wdata.poke(0.U)
    dut.io.in.resp.ready.poke(false.B)
    dut.io.in.flush.poke(false.B)

    dut.io.out.req.ready.poke(false.B)
    dut.io.out.rsp.valid.poke(false.B)
    dut.io.out.rsp.bits.data.poke(0.U)
    dut.io.out.rsp.bits.err.poke(false.B)
  }

  private def pokeReq(dut: CL1LSU, memType: Int, addr: BigInt, wdata: BigInt = 0): Unit = {
    dut.io.in.req.valid.poke(true.B)
    dut.io.in.req.bits.memType.poke(memType.U(4.W))
    dut.io.in.req.bits.addr.poke(addr.U(32.W))
    dut.io.in.req.bits.wdata.poke(wdata.U(32.W))
  }

  private def byteLaneMask(mask: Int): BigInt =
    (0 until 4).foldLeft(BigInt(0)) { (acc, lane) =>
      if (((mask >> lane) & 1) == 1) acc | (BigInt(0xff) << (lane * 8)) else acc
    }

  private def alignedStoreData(wdata: BigInt, addr: BigInt, sizeBytes: Int): BigInt = {
    val offset = (addr & 0x3).toInt
    val value = sizeBytes match {
      case 1 => wdata & 0xff
      case 2 => wdata & 0xffff
      case 4 => wdata & 0xffffffffL
    }
    (value << (offset * 8)) & 0xffffffffL
  }

  private def expectReq(dut: CL1LSU, req: ReqCase): Unit = {
    dut.io.in.req.ready.expect(true.B)
    dut.io.out.req.valid.expect(true.B)
    dut.io.out.req.bits.addr.expect(req.addr.U(32.W))
    dut.io.out.req.bits.wen.expect(req.wen.B)
    dut.io.out.req.bits.mask.expect(req.mask.U(4.W))
    dut.io.out.req.bits.size.expect(req.size.U(2.W))
    dut.io.out.req.bits.cache.expect(req.cache.B)

    if (req.checkStoreData) {
      val selected = byteLaneMask(req.mask)
      val expected = alignedStoreData(req.wdata, req.addr, 1 << req.size)
      val actual = dut.io.out.req.bits.data.peek().litValue
      assert(
        (actual & selected) == (expected & selected),
        f"${req.name}: selected write bytes were 0x${actual & selected}%08x, expected 0x${expected & selected}%08x"
      )
    }
  }

  private def issueReq(dut: CL1LSU, req: ReqCase): Unit = {
    pokeReq(dut, req.memType, req.addr, req.wdata)
    dut.io.out.req.ready.poke(true.B)
    dut.io.in.resp.ready.poke(false.B)
    dut.io.out.rsp.valid.poke(false.B)
    expectReq(dut, req)
    dut.clock.step()
    dut.io.in.req.valid.poke(false.B)
    dut.io.out.req.ready.poke(false.B)
  }

  private def consumeResponse(
      dut: CL1LSU,
      data: BigInt,
      expectedRdata: Option[BigInt] = None,
      err: Boolean = false): Unit = {
    dut.io.out.rsp.valid.poke(true.B)
    dut.io.out.rsp.bits.data.poke(data.U(32.W))
    dut.io.out.rsp.bits.err.poke(err.B)
    dut.io.in.resp.ready.poke(true.B)

    dut.io.in.resp.valid.expect(true.B)
    dut.io.out.rsp.ready.expect(true.B)
    expectedRdata.foreach(value => dut.io.in.resp.bits.rdata.expect(value.U(32.W)))
    dut.io.in.resp.bits.err.expect(err.B)

    dut.clock.step()
    dut.io.out.rsp.valid.poke(false.B)
    dut.io.in.resp.ready.poke(false.B)
  }

  private def transact(dut: CL1LSU, req: ReqCase, rspData: BigInt = 0, expectedRdata: Option[BigInt] = None): Unit = {
    issueReq(dut, req)
    dut.io.memNotOutStanding.expect(false.B)
    consumeResponse(dut, rspData, expectedRdata)
    dut.io.memNotOutStanding.expect(true.B)
  }

  "CL1LSU translates legal LSU requests to CoreBus requests" in {
    test(new CL1LSU()) { dut =>
      init(dut)
      dut.io.memNotOutStanding.expect(true.B)

      val wdata = BigInt("deadbeef", 16)
      val cases = Seq(
        ReqCase("LB byte lane 0", Mem.LB, cachedBase + 0, 0, 0x1, 0, wen = false, cache = true),
        ReqCase("LBU byte lane 3", Mem.LBU, cachedBase + 3, 0, 0x8, 0, wen = false, cache = true),
        ReqCase("LH low half", Mem.LH, cachedBase + 0, 0, 0x3, 1, wen = false, cache = true),
        ReqCase("LHU high half", Mem.LHU, cachedBase + 2, 0, 0xc, 1, wen = false, cache = true),
        ReqCase("LW word", Mem.LW, cachedBase + 0, 0, 0xf, 2, wen = false, cache = true),
        ReqCase("SB byte lane 0", Mem.SB, cachedBase + 0, wdata, 0x1, 0, wen = true, cache = true, checkStoreData = true),
        ReqCase("SB byte lane 1", Mem.SB, cachedBase + 1, wdata, 0x2, 0, wen = true, cache = true, checkStoreData = true),
        ReqCase("SB byte lane 2", Mem.SB, cachedBase + 2, wdata, 0x4, 0, wen = true, cache = true, checkStoreData = true),
        ReqCase("SB byte lane 3", Mem.SB, cachedBase + 3, wdata, 0x8, 0, wen = true, cache = true, checkStoreData = true),
        ReqCase("SH low half", Mem.SH, cachedBase + 0, wdata, 0x3, 1, wen = true, cache = true, checkStoreData = true),
        ReqCase("SH high half", Mem.SH, cachedBase + 2, wdata, 0xc, 1, wen = true, cache = true, checkStoreData = true),
        ReqCase("SW cached word", Mem.SW, cachedBase + 0, wdata, 0xf, 2, wen = true, cache = true, checkStoreData = true),
        ReqCase("SW non-cacheable word", Mem.SW, mmioBase, wdata, 0xf, 2, wen = true, cache = false, checkStoreData = true)
      )

      cases.foreach(req => transact(dut, req))
    }
  }

  "CL1LSU selects load lanes and performs signed or unsigned extension" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val cases = Seq(
        LoadCase("LB lane 0 sign extends", Mem.LB, cachedBase + 0, BigInt("00000080", 16), BigInt("ffffff80", 16)),
        LoadCase("LB lane 1 sign extends", Mem.LB, cachedBase + 1, BigInt("00008000", 16), BigInt("ffffff80", 16)),
        LoadCase("LB lane 2 sign extends", Mem.LB, cachedBase + 2, BigInt("00800000", 16), BigInt("ffffff80", 16)),
        LoadCase("LB lane 3 sign extends", Mem.LB, cachedBase + 3, BigInt("80000000", 16), BigInt("ffffff80", 16)),
        LoadCase("LB positive value stays positive", Mem.LB, cachedBase + 2, BigInt("007f0000", 16), BigInt("0000007f", 16)),
        LoadCase("LBU lane 0 zero extends", Mem.LBU, cachedBase + 0, BigInt("00000080", 16), BigInt("00000080", 16)),
        LoadCase("LBU lane 3 zero extends", Mem.LBU, cachedBase + 3, BigInt("80000000", 16), BigInt("00000080", 16)),
        LoadCase("LH low half sign extends", Mem.LH, cachedBase + 0, BigInt("00008000", 16), BigInt("ffff8000", 16)),
        LoadCase("LH high half sign extends", Mem.LH, cachedBase + 2, BigInt("80000000", 16), BigInt("ffff8000", 16)),
        LoadCase("LH positive value stays positive", Mem.LH, cachedBase + 2, BigInt("7fff0000", 16), BigInt("00007fff", 16)),
        LoadCase("LHU low half zero extends", Mem.LHU, cachedBase + 0, BigInt("00008000", 16), BigInt("00008000", 16)),
        LoadCase("LHU high half zero extends", Mem.LHU, cachedBase + 2, BigInt("80000000", 16), BigInt("00008000", 16)),
        LoadCase("LW returns the whole word", Mem.LW, cachedBase + 0, BigInt("89abcdef", 16), BigInt("89abcdef", 16))
      )

      cases.foreach { load =>
        val req = ReqCase(load.name, load.memType, load.addr, 0, 0, 0, wen = false, cache = true)
        issueReq(dut, req.copy(mask = expectedMask(load.addr, load.memType), size = expectedSize(load.memType)))
        consumeResponse(dut, load.rspData, Some(load.expectedRdata))
      }
    }
  }

  "CL1LSU propagates bus response errors to writeback" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val req = ReqCase("LW with bus error", Mem.LW, cachedBase, 0, 0xf, 2, wen = false, cache = true)
      issueReq(dut, req)
      consumeResponse(dut, BigInt("12345678", 16), Some(BigInt("12345678", 16)), err = true)
    }
  }

  "CL1LSU latches request type and lane until the matching response returns" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val req = ReqCase("old LB lane 3", Mem.LB, cachedBase + 3, 0, 0x8, 0, wen = false, cache = true)
      issueReq(dut, req)

      dut.io.in.req.bits.memType.poke(Mem.LW.U(4.W))
      dut.io.in.req.bits.addr.poke(cachedBase.U(32.W))
      dut.io.in.req.bits.wdata.poke(BigInt("ffffffff", 16).U(32.W))

      consumeResponse(dut, BigInt("80000000", 16), Some(BigInt("ffffff80", 16)))
    }
  }

  "CL1LSU enforces one outstanding transaction and handles backpressure" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val first = ReqCase("first LW", Mem.LW, cachedBase, 0, 0xf, 2, wen = false, cache = true)
      val next  = ReqCase("next LW", Mem.LW, cachedBase + 4, 0, 0xf, 2, wen = false, cache = true)

      issueReq(dut, first)

      pokeReq(dut, next.memType, next.addr, next.wdata)
      dut.io.out.req.ready.poke(true.B)
      dut.io.out.rsp.valid.poke(false.B)
      dut.io.in.resp.ready.poke(true.B)
      dut.io.in.req.ready.expect(false.B)
      dut.io.out.req.valid.expect(false.B)
      dut.io.memNotOutStanding.expect(false.B)

      dut.io.out.rsp.valid.poke(true.B)
      dut.io.out.rsp.bits.data.poke(BigInt("11111111", 16).U(32.W))
      dut.io.in.resp.ready.poke(false.B)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.out.rsp.ready.expect(false.B)
      dut.io.in.req.ready.expect(false.B)
      dut.io.out.req.valid.expect(false.B)

      dut.io.in.resp.ready.poke(true.B)
      expectReq(dut, next)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.out.rsp.ready.expect(true.B)
      dut.io.in.resp.bits.rdata.expect(BigInt("11111111", 16).U(32.W))
      dut.io.memNotOutStanding.expect(true.B)
      dut.clock.step()

      dut.io.in.req.valid.poke(false.B)
      dut.io.out.rsp.valid.poke(false.B)
      dut.io.in.resp.ready.poke(false.B)
      dut.io.memNotOutStanding.expect(false.B)

      consumeResponse(dut, BigInt("22222222", 16), Some(BigInt("22222222", 16)))
      dut.io.memNotOutStanding.expect(true.B)
    }
  }

  "CL1LSU applies external request backpressure without changing outstanding state" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val req = ReqCase("backpressured LW", Mem.LW, cachedBase, 0, 0xf, 2, wen = false, cache = true)
      pokeReq(dut, req.memType, req.addr, req.wdata)
      dut.io.out.req.ready.poke(false.B)

      dut.io.memNotOutStanding.expect(true.B)
      dut.io.in.req.ready.expect(false.B)
      dut.io.out.req.valid.expect(true.B)
      dut.io.out.req.bits.addr.expect(req.addr.U(32.W))

      dut.clock.step()
      dut.io.memNotOutStanding.expect(true.B)

      dut.io.out.req.ready.poke(true.B)
      expectReq(dut, req)
      dut.clock.step()
      dut.io.in.req.valid.poke(false.B)

      consumeResponse(dut, BigInt("abcdef01", 16), Some(BigInt("abcdef01", 16)))
    }
  }

  "CL1LSU drops flushed responses while draining the external response channel" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val flushed = ReqCase("flushed LW", Mem.LW, cachedBase, 0, 0xf, 2, wen = false, cache = true)
      val next    = ReqCase("post-flush LW", Mem.LW, cachedBase + 4, 0, 0xf, 2, wen = false, cache = true)

      issueReq(dut, flushed)

      dut.io.in.flush.poke(true.B)
      dut.io.out.rsp.valid.poke(false.B)
      dut.io.in.resp.valid.expect(false.B)
      dut.io.out.rsp.ready.expect(true.B)
      dut.clock.step()

      dut.io.in.flush.poke(false.B)
      pokeReq(dut, next.memType, next.addr, next.wdata)
      dut.io.out.req.ready.poke(true.B)
      dut.io.out.rsp.valid.poke(true.B)
      dut.io.out.rsp.bits.data.poke(BigInt("aaaaaaaa", 16).U(32.W))
      dut.io.in.resp.ready.poke(false.B)

      dut.io.in.resp.valid.expect(false.B)
      dut.io.out.rsp.ready.expect(true.B)
      expectReq(dut, next)
      dut.clock.step()

      dut.io.in.req.valid.poke(false.B)
      dut.io.out.req.ready.poke(false.B)
      dut.io.out.rsp.valid.poke(false.B)
      dut.io.memNotOutStanding.expect(false.B)

      consumeResponse(dut, BigInt("33333333", 16), Some(BigInt("33333333", 16)))
      dut.io.memNotOutStanding.expect(true.B)
    }
  }

  "CL1LSU suppresses writeback when flush and response arrive in the same cycle" in {
    test(new CL1LSU()) { dut =>
      init(dut)

      val req = ReqCase("same-cycle flushed LW", Mem.LW, cachedBase, 0, 0xf, 2, wen = false, cache = true)
      issueReq(dut, req)

      dut.io.in.flush.poke(true.B)
      dut.io.out.rsp.valid.poke(true.B)
      dut.io.out.rsp.bits.data.poke(BigInt("44444444", 16).U(32.W))
      dut.io.in.resp.ready.poke(true.B)

      dut.io.in.resp.valid.expect(false.B)
      dut.io.out.rsp.ready.expect(true.B)
      dut.io.memNotOutStanding.expect(true.B)
      dut.clock.step()

      dut.io.in.flush.poke(false.B)
      dut.io.out.rsp.valid.poke(false.B)
      dut.io.memNotOutStanding.expect(true.B)
    }
  }

  private def expectedMask(addr: BigInt, memType: Int): Int = {
    val offset = (addr & 0x3).toInt
    (memType >> 1) & 0x3 match {
      case 1 => 1 << offset
      case 2 => if (offset == 0) 0x3 else 0xc
      case 3 => 0xf
    }
  }

  private def expectedSize(memType: Int): Int =
    ((memType >> 1) & 0x3) match {
      case 1 => 0
      case 2 => 1
      case 3 => 2
    }
}
