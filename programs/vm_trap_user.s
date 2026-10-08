# Machine mode builds Sv48-inspired SATP state, fences translations, then enters
# U mode at a virtual address mapped to the ROM-backed physical page.
    LI    R7, 0x100
    CSRW  R7, MTVEC

    LI    R1, 9
    LI    R2, 60
    SLL   R1, R1, R2
    ADDI  R1, R1, 1          # MODE=9, root PPN=1 (physical 0x1000)
    CSRW  R1, SATP
    CSRR  R8, SATP            # readback must observe the committed root/mode
    SFENCE.VMA R0, R0

    LI    R3, 0x00400080
    CSRW  R3, MEPC
    LI    R4, 0
    CSRW  R4, MSTATUS         # MPP=U
    MRET

    .org 0x80
user_start:
    LI    R2, 0x00402100      # virtual data page maps to physical 0x0000
    LI    R3, 0x5A
    STORE R3, R2, 0
    LOAD  R4, R2, 0
    OUT   R4

    LI    R5, 0x00600000      # unmapped VPN: load page fault
    LOAD  R6, R5, 0
    HALT                       # must not execute in U mode

    .org 0x100
machine_trap:
    HALT
