# Timer interrupt demonstration.
# The one-shot timer fires, a vectored ISR increments R10 and clears the pending bit,
# then IRET returns to the foreground loop. The foreground writes 1 to GPIO.
    DI                       # reset starts disabled; test explicit masking too
    LI    R3, 0x101          # MTVEC base 0x100, vectored mode
    CSRW  R3, MTVEC
    LI    R10, 0
    LI    R1, 30
    STORE R1, R0, 0xFE0   # timer compare
    LI    R2, 3
    STORE R2, R0, 0xFC0   # enable + interrupt enable; one-shot mode
    EI

wait_for_irq:
    BEQ   R10, R0, wait_for_irq
    OUT   R10
    HALT

    .org 0x158              # 0x100 + machine-external cause 11 * 8
 timer_handler:
    ADDI  R10, R10, 1
    LI    R2, 1
    STORE R2, R0, 0xFF0   # write-one-to-clear timer pending status
    IRET
