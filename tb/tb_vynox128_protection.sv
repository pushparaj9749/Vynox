`timescale 1ns/1ps

module tb_vynox128_protection;
    localparam logic [7:0] OP_STORE = 8'h11;

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
    integer denied_bus_requests;

    function automatic logic [63:0] encode_store(
        input logic [4:0] source,
        input logic [4:0] base,
        input logic [31:0] offset
    );
        encode_store = {OP_STORE, 5'd0, base, source, offset, 9'd0};
    endfunction

    vynox128_soc #(
        .PROGRAM_FILE("build/user_protection.hex"),
        .MEMORY_WAIT_CYCLES(2)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx),
        .gpio_out(gpio_out), .uart_tx(uart_tx), .timer_irq(timer_irq),
        .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    always @(posedge clk) begin
        if (reset) begin
            denied_bus_requests <= 0;
        end else if (dut.core_data_valid && (dut.core_data_address == 64'h800)) begin
            denied_bus_requests <= denied_bus_requests + 1;
        end
    end

    task automatic check_trap(input logic [63:0] expected_cause);
        begin
            wait (halted === 1'b1);
            @(negedge clk);
            if (fault !== 1'b1 || fault_code !== 8'h07) begin
                $fatal(1, "User protection violation reported wrong fault (%b/%h)", fault, fault_code);
            end
            if (dut.core.mcause !== expected_cause || dut.core.mepc !== 64'h58 ||
                dut.core.mtval !== 64'h800) begin
                $fatal(1, "User access trap state mismatch: cause=%h mepc=%h mtval=%h",
                       dut.core.mcause, dut.core.mepc, dut.core.mtval);
            end
            if (dut.core.privilege_mode !== 2'b11 || denied_bus_requests != 0) begin
                $fatal(1, "Protection trap entered wrong mode or leaked onto data bus");
            end
            if (dut.data_ram[8'h80] !== 128'd0) begin
                $fatal(1, "Denied user access modified machine-only RAM");
            end
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        denied_bus_requests = 0;
        #17 reset = 1'b0;

        // First exercise an unauthorized user-mode load.
        check_trap(64'd5);
        if (dut.data_ram[8'h10] !== 128'h5A || dut.core.registers[5] !== 128'h5A) begin
            $fatal(1, "U-mode access to its allowed RAM window failed");
        end

        // Re-run with the same instruction slot changed to STORE, checking that
        // denied stores are stopped before they can reach the MMIO/RAM bus.
        @(negedge clk);
        reset = 1'b1;
        dut.instruction_rom[11] = encode_store(5'd3, 5'd0, 32'h00000800);
        @(negedge clk);
        reset = 1'b0;
        check_trap(64'd7);

        $display("PASS: U-mode RAM window, protected load/store traps, and no denied bus side effects");
        $finish;
    end

    initial begin
        #30000;
        $fatal(1, "Timed out in Vynox-128 user protection test");
    end
endmodule
