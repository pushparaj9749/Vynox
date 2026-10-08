`timescale 1ns/1ps

module tb_vynox128_uart;
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
    logic [7:0] received [0:2];
    integer frame_index;
    integer bit_index;

    vynox128_soc #(
        .PROGRAM_FILE("build/uart_hello.hex"),
        .MEMORY_WAIT_CYCLES(2),
        .UART_DIVISOR(4)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx), .gpio_out(gpio_out),
        .uart_tx(uart_tx), .timer_irq(timer_irq),
        .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    task automatic receive_uart_byte(output logic [7:0] value);
        begin
            wait (uart_tx === 1'b0);
            #20; // sample the middle of the start bit
            if (uart_tx !== 1'b0) $fatal(1, "UART start bit was not low");
            for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
                #40;
                value[bit_index] = uart_tx;
            end
            #40;
            if (uart_tx !== 1'b1) $fatal(1, "UART stop bit was not high");
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        for (frame_index = 0; frame_index < 3; frame_index = frame_index + 1) begin
            received[frame_index] = 8'd0;
        end
        #17 reset = 1'b0;

        fork
            begin
                receive_uart_byte(received[0]);
                receive_uart_byte(received[1]);
                receive_uart_byte(received[2]);
            end
            begin
                wait (halted === 1'b1);
                wait (dut.uart.tx_busy === 1'b0);
            end
        join

        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "UART program faulted with code %02h", fault_code);
        end
        if (received[0] !== 8'h48 || received[1] !== 8'h69 || received[2] !== 8'h21) begin
            $fatal(1, "UART bytes incorrect: %02h %02h %02h", received[0], received[1], received[2]);
        end
        if (uart_tx !== 1'b1) $fatal(1, "UART TX should return to idle-high");

        $display("PASS: memory-mapped UART transmitted Hi! as 8N1 frames");
        $finish;
    end

    initial begin
        #20000;
        $fatal(1, "Timed out in Vynox-128 UART test");
    end
endmodule
