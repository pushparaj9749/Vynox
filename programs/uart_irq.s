# Enable the UART RX interrupt. The foreground spins while the ISR reads
# one received byte (read-to-clear), sends it to GPIO, and returns with IRET.
    LI    R1, 3              # UART enable + RX IRQ enable
    STORE R1, R0, 0xFB0
    EI

wait_for_rx:
    NOP
    BEQ   R0, R0, wait_for_rx

    .org 0x100
uart_rx_handler:
    LOAD  R2, R0, 0xF70
    OUT   R2
    IRET
