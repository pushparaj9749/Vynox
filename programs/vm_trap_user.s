# M-mode builds page tables with ordinary RAM stores, enables translation, and
# enters U-mode. The ECALL handler changes a warmed data mapping and uses
# SFENCE.VMA before returning, so the second access must see the new PPN.
    LI    R7, 0x200
    CSRW  R7, MTVEC

    LI    R11, 12

    # Root PPN 1: VPN[3]=0 -> second-level table PPN 2.
    LI    R10, 2
    SLL   R10, R10, R11
    ORI   R10, R10, 1
    LI    R12, 0x1000
    STORE R10, R12, 0

    # PPN 2: VPN[2]=0 -> third-level table PPN 3.
    LI    R10, 3
    SLL   R10, R10, R11
    ORI   R10, R10, 1
    LI    R12, 0x2000
    STORE R10, R12, 0

    # PPN 3: VPN[1]=2 -> PPN 4; VPN[1]=3 -> PPN 8 (PTW-fault test).
    LI    R10, 4
    SLL   R10, R10, R11
    ORI   R10, R10, 1
    LI    R12, 8
    SLL   R12, R12, R11
    ORI   R12, R12, 1
    LI    R13, 64
    SLL   R12, R12, R13
    OR    R10, R10, R12
    LI    R14, 0x3010
    STORE R10, R14, 0

    # PPN 4: executable text leaf at index 0 and data leaf at index 2.
    LI    R10, 0x7F          # V,R,W,X,U,A,D; testbench can clear this leaf
    LI    R14, 0x4000
    STORE R10, R14, 0
    LI    R14, 0x4010
    STORE R10, R14, 0

    FENCE                    # PTE stores must reach backing RAM before SATP

    LI    R1, 9
    LI    R2, 60
    SLL   R1, R1, R2
    ADDI  R1, R1, 1          # MODE=9, root PPN=1
    CSRW  R1, SATP
    CSRR  R8, SATP
    SFENCE.VMA R0, R0

    LI    R3, 0x00400300
    CSRW  R3, MEPC
    LI    R4, 0
    CSRW  R4, MSTATUS         # MPP=U
    MRET

    .org 0x200
machine_trap:
    CSRR  R15, MCAUSE
    ADDI  R16, R15, -8
    BNE   R16, R0, trap_stop  # handle ECALL once; stop on the later page fault

    # Change data VA 0x0040_2100 from PPN 0 to PPN 5 (physical 0x5000).
    LI    R10, 5
    LI    R11, 12
    SLL   R10, R10, R11
    ORI   R10, R10, 0x7F
    LI    R14, 0x4010
    STORE R10, R14, 0
    FENCE
    SFENCE.VMA R0, R0

    LI    R3, 0x00400330    # resume just after the U-mode ECALL
    CSRW  R3, MEPC
    MRET

trap_stop:
    HALT

    .org 0x300
user_start:
    LI    R2, 0x00402100      # initially maps to physical 0x0000
    LI    R3, 0x5A
    STORE R3, R2, 0
    LOAD  R4, R2, 0
    OUT   R4
    ECALL                     # M handler changes this warmed mapping

user_after_sfence:
    LI    R3, 0xA5
    STORE R3, R2, 0
    LOAD  R4, R2, 0
    OUT   R4

    LI    R5, 0x00403000      # unmapped L0 leaf: load page fault
    LOAD  R6, R5, 0
    HALT                       # must not execute in U mode
