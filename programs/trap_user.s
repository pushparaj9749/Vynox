# Machine mode installs the direct trap vector, then enters user mode.
    LI    R7, 0x104      # MTVEC base is instruction-word aligned to 8 bytes
    CSRW  R7, MTVEC
    CSRR  R8, MTVEC       # CSR read-after-write must see committed value
    CSRR  R9, MCYCLE
    CSRR  R14, MINSTRET
    LI    R1, 0x80
    CSRW  R1, MEPC
    LI    R2, 0            # MPP=U, MIE remains disabled
    CSRW  R2, MSTATUS
    MRET

    .org 0x80
user_start:
    CSRR  R3, MCAUSE       # illegal in U mode -> cause 2
    CSRW  R0, MEPC         # CSR writes are protected in U mode too
    ECALL                  # user environment call -> cause 8
    LI    R4, 0xA5
    OUT   R4
user_spin:
    BEQ   R0, R0, user_spin

    .org 0x100
machine_trap:
    CSRR  R10, MCAUSE
    ADDI  R13, R10, -2
    BNE   R13, R0, trap_cause_recorded
    ADDI  R12, R10, 0      # preserve the first trap cause (2)
trap_cause_recorded:
    CSRR  R11, MEPC
    ADDI  R11, R11, 8      # skip the faulting CSR read or ECALL
    CSRW  R11, MEPC
    MRET
