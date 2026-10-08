# Sum the integers 1..10, store/reload the 128-bit result, then expose it on GPIO.
# Expected gpio_out: 0x00000000000000000000000000000037 (55 decimal).
    ADDI R1, R0, 10       # loop counter
    ADDI R2, R0, 0        # running sum
    ADDI R3, R0, 1        # current value

sum_loop:
    ADD  R2, R2, R3
    ADDI R3, R3, 1
    ADDI R1, R1, -1
    BNE  R1, R0, sum_loop

    STORE R2, R0, 0x100
    FENCE
    FENCE.I
    LOAD  R4, R0, 0x100
    OUT   R4
    HALT
