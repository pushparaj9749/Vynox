`timescale 1ns/1ps

module tb_vynox128_uart_irq;
    localparam integer BIT_PERIOD_NS = 160;

    logic clk;
    logic reset;
    logic [127:0] gpio_in;
    logic uart_rx;
    wire [127:0] gpio_out;
    wire uart_tx;
    wire timer_irq;
    wire halted;
    wire fault;
    wire [7:0] fault_code;
    integer bit_index;

    vynox128_soc #(
        .PROGRAM_FILE("build/uart_irq.hex"),
        .MEMORY_WAIT_CYCLES(2),
        .UART_DIVISOR(16)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx),
        .uart_tx(uart_tx), .gpio_out(gpio_out), .timer_irq(timer_irq),
        .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    task automatic send_uart_byte(input logic [7:0] value);
        begin
            uart_rx = 1'b0;
            #(BIT_PERIOD_NS);
            for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
                uart_rx = value[bit_index];
                #(BIT_PERIOD_NS);
            end
            uart_rx = 1'b1;
            #(BIT_PERIOD_NS);
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        #17 reset = 1'b0;
        #300; // let firmware enable interrupts before the byte arrives
        send_uart_byte(8'hA5);

        wait (gpio_out === 128'hA5);
        wait (dut.core.in_interrupt === 1'b0);
        @(negedge clk);
        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "UART IRQ program faulted with code %02h", fault_code);
        end
        if (dut.uart.rx_valid !== 1'b0 || dut.uart.rx_error !== 1'b0) begin
            $fatal(1, "UART IRQ handler did not consume a clean RX byte");
        end
        if (dut.uart.rx_irq !== 1'b0) $fatal(1, "UART RX IRQ stayed asserted after read");

        $display("PASS: UART RX interrupt, ISR data read, GPIO, and IRET");
        $finish;
    end

    initial begin
        #30000;
        $fatal(1, "Timed out in Vynox-128 UART interrupt test");
    end
endmodule
