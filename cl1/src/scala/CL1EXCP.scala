package cl1
import chisel3._
import chisel3.util._
import CL1Config._

trait TrapCode {

  val IRQ_BIT = 31
  val M_SFTER_IRQ           = 1.U(1.W) ## 3.U(31.W)
  val M_TIMER_IRQ           = 1.U(1.W) ## 7.U(31.W)
  val M_EXTER_IRQ           = 1.U(1.W) ## 11.U(31.W)
  val INST_MISALIGNED_EXPT  = 0.U(1.W) ## 0.U(31.W)
  val INST_ACCESS_EXPT      = 0.U(1.W) ## 1.U(31.W)
  val INST_ILLEGAL_EXPT     = 0.U(1.W) ## 2.U(31.W)
  val BREAKPOINT_EXPT       = 0.U(1.W) ## 3.U(31.W)
  val LOAD_MISALIGNED_EXPT  = 0.U(1.W) ## 4.U(31.W)
  val LOAD_ACCESS_EXPT      = 0.U(1.W) ## 5.U(31.W)
  val STORE_MISALIGNED_EXPT = 0.U(1.W) ## 6.U(31.W)
  val STORE_ACCESS_EXPT     = 0.U(1.W) ## 7.U(31.W)
  val U_ECALL_EXPT          = 0.U(1.W) ## 8.U(31.W)
  val S_ECALL_EXPT          = 0.U(1.W) ## 9.U(31.W)
  val M_ECALL_EXPT          = 0.U(1.W) ## 11.U(31.W)
}

class wb2Excp extends Bundle {
    val cmt_ecall     = Input(Bool())
    val cmt_mret      = Input(Bool())
    val wb_valid      = Input(Bool())
    val wb_pc         = Input(UInt(32.W))
    val excp_valid    = Input(Bool())
    val excp_code     = Input(UInt(8.W))
    val excp_tval     = Input(UInt(32.W))
}

class excp2Csr extends Bundle {
    val meie        = Input(Bool())
    val msie        = Input(Bool())
    val mtie        = Input(Bool())
    val irq_enable  = Input(Bool())
    val mip         = Input(UInt(32.W))
    val mepc        = Input(UInt(32.W))
    val mcause      = Input(UInt(32.W))
    val mtvec       = Input(UInt(32.W))
    val cmt_epc_en      = Output(Bool())
    val cmt_epc_n       = Output(UInt(32.W))
    val cmt_status_en   = Output(Bool())
    val cmt_cause_en    = Output(Bool())
    val cmt_cause_n     = Output(UInt(32.W))
    val cmt_tval_en     = Output(Bool())
    val cmt_tval_n      = Output(UInt(32.W))
    val cmt_mret_en     = Output(Bool())
}

class dbg2excp extends Bundle {
    val debug_mode      = Input(Bool())
    val debug_irq_mask  = Input(Bool())
    val ebrk_excp_en    = Input(Bool())
    val debug_take_req  = Input(Bool())
}


class CL1EXCPIO() extends Bundle {
    val flush               = Output(Bool())
    val flush_pc            = Output(UInt(32.W))
    val flush_ofst          = Output(UInt(32.W))
    val next_pc             = Input(UInt(32.W))
    val dx_valid            = Input(Bool())
    val ifu_stall           = Output(Bool())
    val dxu_stall           = Output(Bool())
    val wfi_wakeup_req      = Output(Bool())
    val excp2Csr            = new excp2Csr()
    val dbg2excp            = new dbg2excp()
    val wb2Excp             = new wb2Excp()
}

class CL1EXCP() extends Module with TrapCode {
    val io = IO(new CL1EXCPIO())

    val meie    = io.excp2Csr.meie
    val msie    = io.excp2Csr.msie
    val mtie    = io.excp2Csr.mtie
    val irq_enable = io.excp2Csr.irq_enable
    val mip     = io.excp2Csr.mip
    val mepc    = io.excp2Csr.mepc
    val mcause  = io.excp2Csr.mcause
    val mtvec   = io.excp2Csr.mtvec
    val next_pc   = io.next_pc
    val dx_valid = io.dx_valid

    val cmt_ecall       = io.wb2Excp.cmt_ecall
    val cmt_mret        = io.wb2Excp.cmt_mret
    val ebrk_excp_en    = io.dbg2excp.ebrk_excp_en
    val wb_valid        = io.wb2Excp.wb_valid
    val wb_pc           = io.wb2Excp.wb_pc
    val excp_valid      = io.wb2Excp.excp_valid
    val excp_code_raw   = io.wb2Excp.excp_code
    val excp_tval_raw   = io.wb2Excp.excp_tval

    val debug_irq_mask  = io.dbg2excp.debug_irq_mask
    val debug_mode      = io.dbg2excp.debug_mode
    val debug_take_req  = io.dbg2excp.debug_take_req

    val meip = mip(11)
    val mtip = mip(7)
    val msip = mip(3)

    val irq_req_raw =   meip & meie |
                        msip & msie |
                        mtip & mtie
    val irq_mask    =   ~irq_enable | debug_irq_mask
    val irq_req     =  irq_req_raw & ~irq_mask
    // MEI > MSI > MTI for simultaneously enabled & pending M-mode interrupts.
    val irq_casue   =  MuxCase(0.U, Seq(
                        (meip & meie) -> M_EXTER_IRQ,
                        (msip & msie) -> M_SFTER_IRQ,
                        (mtip & mtie) -> M_TIMER_IRQ
    ))

    val excp_req    = cmt_ecall | ebrk_excp_en | excp_valid
    val excp_csr_save_en = excp_req
    val excp_cause       = MuxCase(0.U, Seq(
                            excp_valid   -> Cat(0.U(24.W), excp_code_raw),
                            ebrk_excp_en -> BREAKPOINT_EXPT,
                            cmt_ecall    -> M_ECALL_EXPT
                        ))
    val trap_exit_en     = cmt_mret
    val irq_csr_save_en      = Wire(Bool())
    val trap_csr_save_en     = irq_csr_save_en | excp_csr_save_en
    val cmt_epc_en       = trap_csr_save_en
    val cmt_epc_n        = Mux(excp_csr_save_en, wb_pc, next_pc)
    val cmt_status_en    = trap_csr_save_en
    val cmt_cause_en     = trap_csr_save_en
    val cmt_cause_n      = Mux(excp_csr_save_en, excp_cause, irq_casue)
    val cmt_tval_en      = trap_csr_save_en
    val cmt_tval_n       = Mux(excp_valid, excp_tval_raw, 0.U)
    val cmt_mret_en      = cmt_mret

    val dxwb_pipeEmpty = !dx_valid && !wb_valid

    val irq_drain_done = dxwb_pipeEmpty
    val irq_drain_take = irq_req & irq_drain_done
    val irq_drain_drop = !irq_req & irq_drain_done

    val sIdle :: sIrqDrain :: sIrqFlush :: sExcpFlush :: Nil = Enum(4)
    val state_en         = WireInit(false.B)
    val state_n          = WireInit(sIdle)
    val state            = RegEnable(state_n, sIdle, state_en)

    // Trap FSM policy:
    // - Exceptions are handled with priority over interrupts.
    // - An interrupt is conceptually inserted between IF and ID/EX: IF is
    //   stalled first, then the FSM waits for ID/EX and WB to drain before
    //   saving CSR state and flushing to the trap vector.
    // - If an exception appears while an interrupt is draining the younger
    //   pipeline state, the exception path takes priority.
    // - Exception flush discards IF and ID/EX. Interrupt flush only needs to
    //   discard IF because ID/EX is empty after drain; using the common flush
    //   path is therefore harmless for interrupts.
    switch(state) {
        is(sIdle) {
            state_en := excp_req | irq_req
            state_n  := MuxCase(state, Seq(
                excp_req -> sExcpFlush,
                irq_req  -> sIrqDrain
            ))
        }
        is(sExcpFlush) {
            state_en := true.B
            state_n  := sIdle
        }
        is(sIrqDrain) {
            state_en := excp_req | dxwb_pipeEmpty
            state_n  := MuxCase(state, Seq(
                excp_req -> sExcpFlush,
                irq_drain_take -> sIrqFlush,
                irq_drain_drop -> sIdle
            ))
        }
        is(sIrqFlush) {
            state_en := true.B
            state_n  := sIdle
        }
    }

    def isState(s: UInt): Bool = state === s

    val stIsIdle      = isState(sIdle)
    val stIsIrqDrain  = isState(sIrqDrain)
    val stIsIrqFlush  = isState(sIrqFlush)
    val stIsExcpFlush = isState(sExcpFlush)
    irq_csr_save_en := stIsIrqDrain & irq_drain_take
    val dxu_stall      = excp_req
    // Stall IF immediately when an interrupt is accepted from idle. This may be
    // the first clock-enabled cycle after WFI, so allowing IF to advance here
    // could let a younger instruction enter the pipeline before the interrupt
    // drain starts. Keeping IF stopped preserves the exception PC saved for
    // the interrupt that wakes WFI as the address after WFI, i.e. PC + 4.
    val ifu_stall      = stIsIrqDrain | (stIsIdle & ~excp_req & irq_req)

    val direct_mode       = (mtvec(1,0) === 0.U)
    val vector_mode       = (mtvec(1,0) === 1.U)
    val mtvec_base        = Cat(mtvec(31,2),0.U(2.W))

    val debug_excp_base         = CL1Config.DBG_EXCP_BASE.U
    val trap_take_flush         = stIsIrqFlush | stIsExcpFlush
    val trap_take_flush_pc      = Mux(debug_mode, debug_excp_base, mtvec_base)
    val is_interrupt            = stIsIrqFlush
    val trap_take_flush_ofst    = Mux(vector_mode & is_interrupt, mcause(3,0) << 2, 0.U)

    val trap_exit_flush         = trap_exit_en
    val trap_exit_flush_pc      = mepc

    val flush              = trap_take_flush | trap_exit_flush
    val flush_pc           = Mux(trap_take_flush, trap_take_flush_pc, trap_exit_flush_pc)
    val flush_ofst         = trap_take_flush_ofst
     
    // WFI can enter sleep only when there is no wakeup request. A pending
    // interrupt is enough to wake WFI, and in debug mode WFI must behave as a
    // NOP, so the power FSM must not enter sleep in either case.
    val wfi_wakeup_req     = irq_req_raw | debug_mode | debug_take_req

    io.excp2Csr.cmt_epc_en := cmt_epc_en
    io.excp2Csr.cmt_epc_n  := cmt_epc_n
    io.excp2Csr.cmt_status_en := cmt_status_en
    io.excp2Csr.cmt_cause_en  := cmt_cause_en
    io.excp2Csr.cmt_cause_n   := cmt_cause_n
    io.excp2Csr.cmt_tval_en   := cmt_tval_en
    io.excp2Csr.cmt_tval_n    := cmt_tval_n
    io.excp2Csr.cmt_mret_en   := cmt_mret_en

    io.flush               := flush
    io.flush_pc            := flush_pc
    io.flush_ofst          := flush_ofst

    io.ifu_stall           := ifu_stall
    io.dxu_stall           := dxu_stall

    io.wfi_wakeup_req      := wfi_wakeup_req
}
