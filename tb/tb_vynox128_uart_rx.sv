`timescale 1ns/1ps

module tb_vynox128_uart_rx;
    localparam integer BIT_PERIOD_NS = 160; // 16 clocks per bit, 10 ns clock period

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
        .PROGRAM_FILE("build/uart_rx.hex"),
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
            uart_rx = 1'b0; // start bit
            #(BIT_PERIOD_NS);
            for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
                uart_rx = value[bit_index];
                #(BIT_PERIOD_NS);
            end
            uart_rx = 1'b1; // stop bit
            #(BIT_PERIOD_NS);
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        #17 reset = 1'b0;
        #100;
        send_uart_byte(8'hA5);

        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "UART RX program faulted with code %02h", fault_code);
        end
        if (gpio_out !== 128'hA5) begin
            $fatal(1, "UART RX expected 0xA5 on GPIO, got %032h", gpio_out);
        end
        if (dut.uart.rx_valid !== 1'b0 || dut.uart.rx_error !== 1'b0) begin
            $fatal(1, "UART RX status did not clear cleanly");
        end

        $display("PASS: UART RX synchronizer, 8N1 decode, status polling, and read-to-clear");
        $finish;
    end

    initial begin
        #30000;
        $fatal(1, "Timed out in Vynox-128 UART RX test");
    end
endmodule
