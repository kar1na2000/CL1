package utils

import chisel3._
import chisel3.util._
import cl1.CL1Config.{SramFoundary, Technology}
import cl1.CL1Technology

class sramIO(val WordDepth:Int = 256, val DW: Int = 32, val BE: Boolean = false) extends Bundle {
    val addr = Input(UInt(log2Ceil(WordDepth).W))
    val din  = Input(UInt(DW.W))
    val wea  = Input(if (BE) UInt((DW/8).W) else Bool())
    val ena  = Input(Bool())
    val dout = Output(UInt(DW.W))
}

class sram(val WordDepth:Int = 256, val DW: Int = 32, val BE: Boolean = false) extends Module {
    val io = IO(new sramIO(WordDepth, DW, BE))

    private val addrWidth = log2Ceil(WordDepth)
    private val useFoundryMacro = SramFoundary
    private val useCx55Macro = CL1Technology.useCx55Memory(Technology)
    private val useSmic100Macro = CL1Technology.useSmic100Memory(Technology)

    private def requireCx55Shape(): Unit = {
        require(WordDepth == 128, s"CX55 SRAM M8 macro only supports WordDepth=128, got ${WordDepth}.")
        require(addrWidth == 7, s"CX55 SRAM M8 macro expects 7 address bits, got ${addrWidth}.")
    }

    private def connectMacro(mem: SMICSramBlackBoxBase): Unit = {
        mem.io.CLK := clock
        mem.io.A := io.addr
        mem.io.D := io.din
        mem.io.CEN := !io.ena
        mem.io.WEN := !(io.wea =/= 0.U)
        io.dout := mem.io.Q
    }

    private def connectCx55Macro(mem: CX55SramBlackBoxBase): Unit = {
        mem.io.CLK := clock
        mem.io.A := io.addr
        mem.io.D := io.din
        mem.io.CEB := !io.ena
        mem.io.GWEB := !(io.wea =/= 0.U)
        mem.io.MARE := false.B
        mem.io.MAR := 0.U
        io.dout := mem.io.Q
    }

    private def byteWriteMask: UInt = {
        Cat(io.wea.asBools.map(b => Fill(8, !b)).reverse)
    }

    private def unsupportedMacroWidth: Nothing = {
        throw new IllegalArgumentException(s"SRAM macro only supports DW=32 or DW=22, got ${DW}.")
    }

    if(BE) {
        if(!useFoundryMacro) {
            val mem = SyncReadMem(WordDepth, Vec(DW/8, UInt(8.W)))
            val dataAsVec = io.din.asTypeOf(Vec(DW/8, UInt(8.W)))
            when(io.ena && io.wea =/= 0.U) {
                mem.write(io.addr, dataAsVec, io.wea.asBools)
            }
            io.dout := mem.read(io.addr, io.ena).asUInt
        } else {
            require(DW == 32, s"Byte-write SRAM macro only supports DW=32, got ${DW}.")
            if (useCx55Macro) {
                requireCx55Shape()
                val mem = Module(new SRAM_128X32_M8_BW(addrWidth, DW))
                connectCx55Macro(mem)
                mem.io.WEB.foreach(_ := byteWriteMask)
            } else {
                val mem =
                    if (useSmic100Macro) Module(new S011HD1P_X64Y2D32_BW(addrWidth, DW))
                    else Module(new S55NLLG1PH_X128Y1D32_BW(addrWidth, DW))

                connectMacro(mem)
                mem.io.BWEN.foreach(_ := byteWriteMask)
            }
        }
    } else {
        if(!useFoundryMacro) {
            val mem = SyncReadMem(WordDepth, UInt(DW.W))
            when(io.ena && io.wea =/= 0.U) {
                mem.write(io.addr, io.din)
            }
            io.dout := mem.read(io.addr, io.ena)
        } else {
            require(DW == 32 || DW == 22, s"SRAM macro only supports DW=32 or DW=22, got ${DW}.")
            if (useCx55Macro) {
                requireCx55Shape()
                val mem = DW match {
                    case 32 => Module(new SRAM_128X32_M8(addrWidth, DW))
                    case 22 => Module(new SRAM_128X22_M8(addrWidth, DW))
                    case _  => unsupportedMacroWidth
                }
                connectCx55Macro(mem)
            } else {
                val mem = (DW, useSmic100Macro) match {
                    case (32, true)  => Module(new S011HD1P_X64Y2D32(addrWidth, DW))
                    case (32, false) => Module(new S55NLLG1PH_X128Y1D32(addrWidth, DW))
                    case (22, true)  => Module(new S011HD1P_X64Y2D22(addrWidth, DW))
                    case (22, false) => Module(new S55NLLG1PH_X128Y1D22(addrWidth, DW))
                    case _           => unsupportedMacroWidth
                }
                connectMacro(mem)
            }
        }
    }
}

class CX55SramIO(AW: Int = 7, DW: Int = 32, hasWEB: Boolean = false) extends Bundle {
    val CLK     = Input(Clock())
    val A       = Input(UInt(AW.W))
    val D       = Input(UInt(DW.W))
    val CEB     = Input(Bool())
    val GWEB    = Input(Bool())
    val WEB     = if (hasWEB) Some(Input(UInt(DW.W))) else None
    val MARE    = Input(Bool())
    val MAR     = Input(UInt(4.W))
    val Q       = Output(UInt(DW.W))
}

abstract class CX55SramBlackBoxBase(name: String, AW: Int, DW: Int, hasWEB: Boolean = false) extends BlackBox {
    val io = IO(new CX55SramIO(AW, DW, hasWEB))
    override def desiredName: String = name
}

class SRAM_128X32_M8(AW: Int = 7, DW: Int = 32)
    extends CX55SramBlackBoxBase("SRAM_128X32_M8", AW, DW, hasWEB = false)
class SRAM_128X32_M8_BW(AW: Int = 7, DW: Int = 32)
    extends CX55SramBlackBoxBase("SRAM_128X32_M8_BW", AW, DW, hasWEB = true)
class SRAM_128X22_M8(AW: Int = 7, DW: Int = 22)
    extends CX55SramBlackBoxBase("SRAM_128X22_M8", AW, DW, hasWEB = false)

class SMICSramIO(AW: Int = 8, DW: Int = 31, hasBWEN: Boolean = false) extends Bundle {
    val CLK     = Input(Clock())
    val A       = Input(UInt(AW.W))
    val D       = Input(UInt(DW.W))
    val CEN     = Input(Bool())
    val WEN     = Input(Bool())
    val BWEN    = if (hasBWEN) Some(Input(UInt(DW.W))) else None
    val Q       = Output(UInt(DW.W))
}

abstract class SMICSramBlackBoxBase(name: String, AW: Int, DW: Int, hasBWEN: Boolean = false) extends BlackBox {
    val io = IO(new SMICSramIO(AW, DW, hasBWEN))
    override def desiredName: String = name
}


class S55NLLG1PH_X128Y1D32_BW(AW: Int = 10, DW: Int = 32)
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D32_BW", AW, DW, hasBWEN = true) 

class S55NLLG1PH_X128Y1D32(AW: Int = 10, DW: Int = 32) 
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D32", AW, DW, hasBWEN = false)
class S55NLLG1PH_X128Y1D22(AW: Int = 10, DW: Int = 22)
    extends SMICSramBlackBoxBase("S55NLLG1PH_X128Y1D22", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D22(AW: Int = 10, DW: Int = 22)
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D22", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D32(AW: Int = 10, DW: Int = 32) 
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D32", AW, DW, hasBWEN = false)
class S011HD1P_X64Y2D32_BW(AW: Int = 10, DW: Int = 32)
    extends SMICSramBlackBoxBase("S011HD1P_X64Y2D32_BW", AW, DW, hasBWEN = true)
