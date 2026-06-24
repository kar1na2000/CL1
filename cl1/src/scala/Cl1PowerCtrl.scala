package cl1
import chisel3._
import chisel3.util._

class PowerCtrlIO extends Bundle {
  val dx_wfi              = Input(Bool())
  val wb_wfi              = Input(Bool())
  val wfi_wakeup_req      = Input(Bool())
  val ifu_idle            = Input(Bool())
  val icache_idle         = Input(Bool())
  val dcache_idle         = Input(Bool())
  val ifu_stall           = Output(Bool())
  val core_sleep          = Output(Bool())
}

class CL1PowerCtrl extends Module {
    val io = IO(new PowerCtrlIO)

    val wfi_sleep_req = io.wb_wfi & ~io.wfi_wakeup_req
    val ready_to_sleep = io.ifu_idle & io.icache_idle & io.dcache_idle

    // Power control logic
    val sAwake :: sEnterAsleep :: sAsleep :: Nil = Enum(3)
    val state_en         = WireInit(false.B)
    val state_n          = WireInit(sAwake)
    val state            = RegEnable(state_n, sAwake, state_en)

    switch(state) {
        is(sAwake) {
            state_en := wfi_sleep_req
            state_n  := Mux(wfi_sleep_req, sEnterAsleep, sAwake)
        }
        is(sEnterAsleep) {
            state_en := io.wfi_wakeup_req | ready_to_sleep
            state_n  := MuxCase(state, Seq(
                io.wfi_wakeup_req -> sAwake, // Wake up if there's a wakeup request
                ready_to_sleep -> sAsleep    // Enter asleep state if ready to sleep
            ))
        }
        is(sAsleep) {
            state_en := io.wfi_wakeup_req
            state_n  := Mux(io.wfi_wakeup_req, sAwake, sAsleep)
        }
    }

    def isState(s: UInt): Bool = state === s
    val stIsAwake = isState(sAwake)
    val stIsEnterAsleep = isState(sEnterAsleep)
    val stIsAsleep = isState(sAsleep)

    // Stall IF as soon as WFI reaches DX. If WFI is later resumed by an
    // interrupt, the interrupt trap must save the PC after WFI (WFI PC + 4).
    // Preventing younger instructions from being fetched lets the interrupt
    // path reuse the normal next-PC bookkeeping and save the architecturally
    // required mepc.
    io.ifu_stall := io.dx_wfi | (stIsAwake & wfi_sleep_req) | stIsEnterAsleep | stIsAsleep
    io.core_sleep :=  stIsAsleep
}
