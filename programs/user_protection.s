# User RAM accesses are allowed; kernel RAM and MMIO are machine-only.
# The SoC test replaces the final user LOAD with a STORE to test both faults.
    LI    R1, 0x40
    CSRW  R1, MEPC
    LI    R2, 0
    CSRW  R2, MSTATUS
    MRET

    .org 0x40
user_start:
    LI    R3, 0x5A
    STORE R3, R0, 0x100   # user RAM: allowed
    LOAD  R5, R0, 0x100   # user RAM: allowed
    LOAD  R4, R0, 0x800   # kernel RAM: load access fault
    HALT

    .org 0x100
machine_trap:
    HALT
