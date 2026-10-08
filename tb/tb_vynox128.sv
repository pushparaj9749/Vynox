`timescale 1ns/1ps

module tb_vynox128;
    localparam logic [7:0] OP_ADD   = 8'h01;
    localparam logic [7:0] OP_ADDI  = 8'h0B;
    localparam logic [7:0] OP_MUL   = 8'h0F;
    localparam logic [7:0] OP_LOAD  = 8'h10;
    localparam logic [7:0] OP_STORE = 8'h11;
    localparam logic [7:0] OP_BEQ   = 8'h20;
    localparam logic [7:0] OP_JAL   = 8'h30;
    localparam logic [7:0] OP_IN    = 8'h40;
    localparam logic [7:0] OP_OUT   = 8'h41;
    localparam logic [7:0] OP_HALT  = 8'hFF;

    logic clk;
    logic reset;
    logic [63:0] instruction;
    logic instruction_error;
    wire [63:0] instruction_address;
    wire [63:0] ex_data_address;
    wire data_valid;
    wire data_write_enable;
    wire [63:0] data_address;
    wire [127:0] data_write_data;
    logic data_ready;
    logic data_error;
    logic [127:0] data_read_data;
    logic [127:0] gpio_in;
    wire [127:0] gpio_out;
    wire halted;
    wire fault;
    wire [7:0] fault_code;

    logic [63:0] rom [0:255];
    logic [127:0] data_memory [0:255];
    integer i;

    function automatic logic [63:0] encode_r(
        input logic [7:0] op,
        input logic [4:0] destination,
        input logic [4:0] source_a,
        input logic [4:0] source_b
    );
        encode_r = {op, destination, source_a, source_b, 32'd0, 9'd0};
    endfunction

    function automatic logic [63:0] encode_i(
        input logic [7:0] op,
        input logic [4:0] destination,
        input logic [4:0] source_a,
        input logic [31:0] imm
    );
        encode_i = {op, destination, source_a, 5'd0, imm, 9'd0};
    endfunction

    function automatic logic [63:0] encode_s(
        input logic [7:0] op,
        input logic [4:0] base,
        input logic [4:0] source_b,
        input logic [31:0] imm
    );
        encode_s = {op, 5'd0, base, source_b, imm, 9'd0};
    endfunction

    function automatic logic [63:0] encode_b(
        input logic [7:0] op,
        input logic [4:0] source_a,
        input logic [4:0] source_b,
        input logic [31:0] offset_in_instructions
    );
        encode_b = {op, 5'd0, source_a, source_b, offset_in_instructions, 9'd0};
    endfunction

    function automatic logic [63:0] encode_j(
        input logic [7:0] op,
        input logic [4:0] destination,
        input logic [31:0] offset_in_instructions
    );
        encode_j = {op, destination, 5'd0, 5'd0, offset_in_instructions, 9'd0};
    endfunction

    vynox128_core dut (
        .clk(clk),
        .reset(reset),
        .irq(1'b0),
        .instruction(instruction),
        .instruction_ready(1'b1),
        .instruction_error(instruction_error),
        .instruction_address(instruction_address),
        .instruction_fault_cause(64'd1), .instruction_fault_address(instruction_address),
        .mmu_flush(), .satp_value(),
        .data_valid(data_valid),
        .data_write_enable(data_write_enable),
        .data_address(data_address),
        .data_write_data(data_write_data),
        .ex_data_address(ex_data_address),
        .data_access_allowed(1'b1), .data_translation_ready(1'b1),
        .data_translation_address(ex_data_address), .data_translation_fault(1'b0),
        .data_translation_fault_cause(64'd0),
        .data_translation_fault_address(ex_data_address),
        .data_ready(data_ready),
        .data_error(data_error),
        .data_read_data(data_read_data),
        .gpio_in(gpio_in),
        .gpio_out(gpio_out),
        .halted(halted),
        .fault(fault),
        .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    always @* begin
        instruction = rom[instruction_address[10:3]];
        data_read_data = data_memory[data_address[11:4]];
    end

    always_ff @(posedge clk) begin
        if (data_valid && data_ready && data_write_enable) begin
            data_memory[data_address[11:4]] <= data_write_data;
        end
    end

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        data_ready = 1'b1;
        data_error = 1'b0;
        instruction_error = 1'b0;
        gpio_in = 128'h0123456789ABCDEFFEDCBA9876543210;

        for (i = 0; i < 256; i = i + 1) begin
            rom[i] = 64'd0;
            data_memory[i] = 128'd0;
        end

        // Build 5 + 3 = 8 and 5 * 3 = 15, store/load 8, then branch
        // over a write to R6. Finally copy a 128-bit GPIO input to output.
        rom[0]  = encode_i(OP_ADDI, 5'd1, 5'd0, 32'd5);
        rom[1]  = encode_i(OP_ADDI, 5'd2, 5'd0, 32'd3);
        rom[2]  = encode_r(OP_ADD,  5'd3, 5'd1, 5'd2);
        rom[3]  = encode_r(OP_MUL,  5'd8, 5'd1, 5'd2);
        rom[4]  = encode_s(OP_STORE, 5'd0, 5'd3, 32'h00000100);
        rom[5]  = encode_i(OP_LOAD, 5'd4, 5'd0, 32'h00000100);
        rom[6]  = encode_i(OP_ADDI, 5'd5, 5'd0, 32'd8);
        rom[7]  = encode_b(OP_BEQ, 5'd4, 5'd5, 32'd2);
        rom[8]  = encode_i(OP_ADDI, 5'd6, 5'd0, 32'd99); // branch skips this
        rom[9]  = encode_i(OP_IN,   5'd7, 5'd0, 32'd0);
        rom[10] = encode_i(OP_OUT,  5'd7, 5'd0, 32'd0);
        rom[11] = encode_j(OP_JAL,  5'd9, 32'd2); // link to 12, jump over 12 to 13
        rom[12] = encode_i(OP_ADDI, 5'd10, 5'd0, 32'd99); // JAL skips this
        rom[13] = encode_i(OP_HALT, 5'd0, 5'd0, 32'd0);

        #17 reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "Unexpected processor fault code %02h", fault_code);
        end
        if (data_memory[8'h10] !== 128'd8) begin
            $fatal(1, "128-bit STORE/LOAD check failed: got %032h", data_memory[8'h10]);
        end
        if (dut.registers[8] !== 128'd15) begin
            $fatal(1, "128-bit MUL check failed: R8=%032h", dut.registers[8]);
        end
        if (dut.registers[6] !== 128'd0) begin
            $fatal(1, "Branch check failed: skipped instruction changed R6 to %032h", dut.registers[6]);
        end
        if (dut.registers[9] !== 128'd96 || dut.registers[10] !== 128'd0) begin
            $fatal(1, "JAL link/target check failed: R9=%032h R10=%032h", dut.registers[9], dut.registers[10]);
        end
        if (gpio_out !== 128'h0123456789ABCDEFFEDCBA9876543210) begin
            $fatal(1, "128-bit GPIO check failed: got %032h", gpio_out);
        end

        $display("PASS: Vynox-128 arithmetic, memory, branch, and 128-bit GPIO");
        $finish;
    end

    initial begin
        #5000;
        $fatal(1, "Timed out waiting for Vynox-128 to halt");
    end
endmodule
