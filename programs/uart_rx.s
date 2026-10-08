# Poll the UART status, receive one byte, and expose it on GPIO.
wait_for_byte:
    LOAD  R1, R0, 0xF90   # UART status; RX-valid is bit 3
    ANDI  R2, R1, 8
    BEQ   R2, R0, wait_for_byte
    LOAD  R3, R0, 0xF70   # UART RX data; read consumes the byte
    OUT   R3
    HALT
