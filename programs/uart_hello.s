# Send the three ASCII bytes "Hi!" over the memory-mapped 8N1 UART TX.
    LI    R1, 0x48
    STORE R1, R0, 0xF80
    LI    R1, 0x69
    STORE R1, R0, 0xF80
    LI    R1, 0x21
    STORE R1, R0, 0xF80
    HALT
