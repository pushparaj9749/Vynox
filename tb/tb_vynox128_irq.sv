`timescale 1ns/1ps

module tb_vynox128_irq;
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

    vynox128_soc #(
        .PROGRAM_FILE("build/timer_irq.hex"),
        .MEMORY_WAIT_CYCLES(2)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx), .gpio_out(gpio_out),
        .uart_tx(uart_tx), .timer_irq(timer_irq), .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        #17 reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "Timer IRQ program faulted with code %02h", fault_code);
        end
        if (gpio_out !== 128'd1) begin
            $fatal(1, "Timer ISR did not increment the interrupt counter: GPIO=%032h", gpio_out);
        end
        if (dut.timer_pending !== 1'b0 || dut.timer_enable !== 1'b0) begin
            $fatal(1, "One-shot timer was not acknowledged/stopped");
        end
        if (dut.core.in_interrupt !== 1'b0) begin
            $fatal(1, "IRET did not leave interrupt context");
        end
        if (dut.core.mtvec !== 64'h101 || dut.core.mcause !== 64'h8000_0000_0000_000B) begin
            $fatal(1, "Vectored machine-external trap state is incorrect");
        end

        $display("PASS: timer MMIO, vectored external IRQ, ISR, W1C acknowledge, and IRET");
        $finish;
    end

    initial begin
        #20000;
        $fatal(1, "Timed out in Vynox-128 timer interrupt test");
    end
endmodule
