`timescale 1ns/1ps

module tb_vynox128_soc;
    localparam logic [7:0] OP_LOAD = 8'h10;
    localparam logic [7:0] OP_JAL  = 8'h30;
    localparam logic [7:0] OP_HALT = 8'hFF;

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
    integer memory_stall_cycles;
    integer instruction_stall_cycles;
    logic fence_i_seen;

    function automatic logic [63:0] encode_i(
        input logic [7:0] op,
        input logic [4:0] destination,
        input logic [4:0] source_a,
        input logic [31:0] imm
    );
        encode_i = {op, destination, source_a, 5'd0, imm, 9'd0};
    endfunction

    function automatic logic [63:0] encode_j(
        input logic [7:0] op,
        input logic [4:0] destination,
        input logic [31:0] offset_in_instructions
    );
        encode_j = {op, destination, 5'd0, 5'd0, offset_in_instructions, 9'd0};
    endfunction

    vynox128_soc #(
        .PROGRAM_FILE("build/sum_1_to_10.hex"),
        .MEMORY_WAIT_CYCLES(3)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx), .gpio_out(gpio_out),
        .uart_tx(uart_tx), .timer_irq(timer_irq), .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    task automatic restart_with(input logic [63:0] first_instruction);
        begin
            @(negedge clk);
            reset = 1'b1;
            dut.instruction_rom[0] = first_instruction;
            dut.instruction_rom[32] = {OP_HALT, 56'd0};
            @(negedge clk);
            reset = 1'b0;
            wait (halted === 1'b1);
            @(negedge clk);
        end
    endtask

    always #5 clk = ~clk;

    always @(posedge clk) begin
        if (reset) begin
            fence_i_seen <= 1'b0;
        end else if (dut.core.instruction_flush) begin
            fence_i_seen <= 1'b1;
        end
        if (dut.core_data_valid && !dut.memory_ready) begin
            memory_stall_cycles <= memory_stall_cycles + 1;
        end
        if (!reset && !dut.core_instruction_ready) begin
            instruction_stall_cycles <= instruction_stall_cycles + 1;
        end
    end

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        memory_stall_cycles = 0;
        instruction_stall_cycles = 0;
        #17 reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b0 || fault_code !== 8'h00) $fatal(1, "SoC reported an unexpected fault code %02h", fault_code);
        if (gpio_out !== 128'd55) begin
            $fatal(1, "sum program expected GPIO=55, got %032h", gpio_out);
        end
        if (dut.data_ram[8'h10] !== 128'd55) begin
            $fatal(1, "sum program expected RAM[16]=55, got %032h", dut.data_ram[8'h10]);
        end
        if (fence_i_seen !== 1'b1) begin
            $fatal(1, "FENCE.I did not invalidate the instruction cache");
        end
        if (memory_stall_cycles < 4) begin
            $fatal(1, "wait-state test did not observe RAM stalls (%0d)", memory_stall_cycles);
        end
        if (instruction_stall_cycles < 4) begin
            $fatal(1, "I-cache test did not observe fetch stalls (%0d)", instruction_stall_cycles);
        end

        restart_with(encode_i(OP_LOAD, 5'd1, 5'd0, 32'h00008000));
        if (fault !== 1'b1 || fault_code !== 8'h03) begin
            $fatal(1, "Out-of-range RAM access did not report data-bus fault (code=%02h)", fault_code);
        end
        if (dut.core.mepc !== 64'd0 || dut.core.mcause !== 64'd5 || dut.core.mtval !== 64'h8000) begin
            $fatal(1, "Unmapped load did not populate machine trap CSRs");
        end

        restart_with(encode_j(OP_JAL, 5'd0, 32'd256));
        if (fault !== 1'b1 || fault_code !== 8'h04) begin
            $fatal(1, "Out-of-range ROM access did not report fetch fault (code=%02h)", fault_code);
        end
        if (dut.core.mepc !== 64'h800 || dut.core.mcause !== 64'd1 || dut.core.mtval !== 64'h800) begin
            $fatal(1, "Fetch fault did not populate machine trap CSRs");
        end

        $display("PASS: Vynox-128 SoC program, wait states, GPIO, and address faults");
        $finish;
    end

    initial begin
        #10000;
        $fatal(1, "Timed out waiting for Vynox-128 SoC to halt");
    end
endmodule
