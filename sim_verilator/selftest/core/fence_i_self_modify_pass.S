.section .text
.globl main

#include "address_map.h"

.equ HOST_EXIT_ADDR, CL1_DEFAULT_HOST_EXIT_ADDR

main:
  la s0, cached_slot
  la s1, patch_value

  jalr s0
  li t0, 1
  bne a0, t0, fail

  lw t1, 0(s1)
  sw t1, 0(s0)

  .word 0x0000100f  /* fence.i */

  jalr s0
  li t0, 2
  bne a0, t0, fail

  li t0, HOST_EXIT_ADDR
  sw zero, 0(t0)

pass_loop:
  j pass_loop

.align 4
cached_slot:
  .word 0x00100513  /* addi a0, zero, 1 */
  .word 0x00008067  /* ret */

patch_value:
  .word 0x00200513  /* addi a0, zero, 2 */

fail:
  li t1, HOST_EXIT_ADDR
  sw t0, 0(t1)

fail_loop:
  j fail_loop
