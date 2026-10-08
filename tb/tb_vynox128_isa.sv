`timescale 1ns/1ps

module tb_vynox128_isa;
    localparam logic [7:0] OP_NOP  = 8'h00;
    localparam logic [7:0] OP_SUB  = 8'h02;
    localparam logic [7:0] OP_AND  = 8'h03;
    localparam logic [7:0] OP_OR   = 8'h04;
    localparam logic [7:0] OP_XOR  = 8'h05;
    localparam logic [7:0] OP_SLL  = 8'h06;
    localparam logic [7:0] OP_SRL  = 8'h07;
    localparam logic [7:0] OP_SRA  = 8'h08;
    localparam logic [7:0] OP_SLT  = 8'h09;
    localparam logic [7:0] OP_SLTU = 8'h0A;
    localparam logic [7:0] OP_ADDI = 8'h0B;
    localparam logic [7:0] OP_ANDI = 8'h0C;
    localparam logic [7:0] OP_ORI  = 8'h0D;
    localparam logic [7:0] OP_XORI = 8'h0E;
    localparam logic [7:0] OP_BLT  = 8'h22;
    localparam logic [7:0] OP_BGE  = 8'h23;
    localparam logic [7:0] OP_BLTU = 8'h24;
    localparam logic [7:0] OP_BGEU = 8'h25;
    localparam logic [7:0] OP_HALT = 8'hFF;

    logic clk;
    logic reset;
    logic [63:0] instruction;
    wire [63:0] instruction_address;
    wire [63:0] ex_data_address;
    wire data_valid;
    wire data_write_enable;
    wire [63:0] data_address;
    wire [127:0] data_write_data;
    logic [63:0] rom [0:255];
    wire [127:0] gpio_out;
    wire halted;
    wire fault;
    wire [7:0] fault_code;
    integer i;

    function automatic logic [63:0] enc_r(
        input logic [7:0] op, input logic [4:0] rd,
        input logic [4:0] rs1, input logic [4:0] rs2
    );
        enc_r = {op, rd, rs1, rs2, 32'd0, 9'd0};
    endfunction

    function automatic logic [63:0] enc_i(
        input logic [7:0] op, input logic [4:0] rd,
        input logic [4:0] rs1, input logic [31:0] imm
    );
        enc_i = {op, rd, rs1, 5'd0, imm, 9'd0};
    endfunction

    function automatic logic [63:0] enc_b(
        input logic [7:0] op, input logic [4:0] rs1,
        input logic [4:0] rs2, input logic [31:0] offset
    );
        enc_b = {op, 5'd0, rs1, rs2, offset, 9'd0};
    endfunction

    vynox128_core dut (
        .clk(clk), .reset(reset), .irq(1'b0), .instruction(instruction),
        .instruction_ready(1'b1),
        .instruction_error(1'b0), .instruction_address(instruction_address),
        .instruction_fault_cause(64'd1), .instruction_fault_address(instruction_address),
        .mmu_flush(), .satp_value(),
        .data_valid(data_valid), .data_write_enable(data_write_enable),
        .data_address(data_address), .data_write_data(data_write_data),
        .ex_data_address(ex_data_address),
        .data_access_allowed(1'b1), .data_translation_ready(1'b1),
        .data_translation_address(ex_data_address), .data_translation_fault(1'b0),
        .data_translation_fault_cause(64'd0),
        .data_translation_fault_address(ex_data_address), .data_ready(1'b1), .data_error(1'b0), .data_read_data(128'd0),
        .gpio_in(128'd0), .gpio_out(gpio_out), .halted(halted),
        .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;
    always @* instruction = rom[instruction_address[10:3]];

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        for (i = 0; i < 256; i = i + 1) rom[i] = 64'd0;

        // Signed values, ALU functions, shifts, and compare/branch families.
        rom[0]  = enc_i(OP_ADDI, 5'd1, 5'd0, 32'hFFFF_FFFB); // R1=-5
        rom[1]  = enc_i(OP_ADDI, 5'd2, 5'd0, 32'd3);          // R2=3
        rom[2]  = enc_r(OP_SUB, 5'd3, 5'd1, 5'd2);             // -8
        rom[3]  = enc_r(OP_AND, 5'd4, 5'd1, 5'd2);             // 3
        rom[4]  = enc_r(OP_OR,  5'd5, 5'd1, 5'd2);             // -5
        rom[5]  = enc_r(OP_XOR, 5'd6, 5'd1, 5'd2);             // -8
        rom[6]  = enc_r(OP_SLL, 5'd7, 5'd2, 5'd2);             // 24
        rom[7]  = enc_r(OP_SRL, 5'd8, 5'd7, 5'd2);             // 3
        rom[8]  = enc_r(OP_SRA, 5'd9, 5'd1, 5'd2);             // -1
        rom[9]  = enc_r(OP_SLT, 5'd10, 5'd1, 5'd2);            // 1
        rom[10] = enc_r(OP_SLTU, 5'd11, 5'd1, 5'd2);           // 0
        rom[11] = enc_i(OP_ANDI, 5'd12, 5'd1, 32'h0F);         // 11
        rom[12] = enc_i(OP_ORI, 5'd13, 5'd0, 32'hFF);          // 255
        rom[13] = enc_i(OP_XORI, 5'd14, 5'd13, 32'h0F);       // 240
        rom[14] = enc_b(OP_BLT, 5'd1, 5'd2, 32'd2);            // taken, skip 15
        rom[15] = enc_i(OP_ADDI, 5'd15, 5'd0, 32'd99);
        rom[16] = enc_b(OP_BGE, 5'd2, 5'd1, 32'd2);            // taken, skip 17
        rom[17] = enc_i(OP_ADDI, 5'd16, 5'd0, 32'd99);
        rom[18] = enc_b(OP_BLTU, 5'd1, 5'd2, 32'd2);           // not taken
        rom[19] = enc_i(OP_ADDI, 5'd17, 5'd0, 32'd7);
        rom[20] = enc_b(OP_BGEU, 5'd1, 5'd2, 32'd2);           // taken, skip 21
        rom[21] = enc_i(OP_ADDI, 5'd18, 5'd0, 32'd99);
        rom[22] = {OP_HALT, 56'd0};

        #17 reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "Unexpected core fault code %02h", fault_code);
        end
        if (dut.registers[3] !== 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFF8) begin
            $fatal(1, "SUB expected -8, got %032h", dut.registers[3]);
        end
        if (dut.registers[4] !== 128'd3 || dut.registers[5] !== 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFB) begin
            $fatal(1, "AND/OR results incorrect");
        end
        if (dut.registers[6] !== 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFF8) begin
            $fatal(1, "XOR result incorrect");
        end
        if (dut.registers[7] !== 128'd24 || dut.registers[8] !== 128'd3 || dut.registers[9] !== 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF) begin
            $fatal(1, "Shift results incorrect");
        end
        if (dut.registers[10] !== 128'd1 || dut.registers[11] !== 128'd0) begin
            $fatal(1, "Signed/unsigned compare results incorrect");
        end
        if (dut.registers[12] !== 128'd11 || dut.registers[13] !== 128'd255 || dut.registers[14] !== 128'd240) begin
            $fatal(1, "Immediate logic results incorrect");
        end
        if (dut.registers[15] !== 128'd0 || dut.registers[16] !== 128'd0 || dut.registers[17] !== 128'd7 || dut.registers[18] !== 128'd0) begin
            $fatal(1, "Signed/unsigned branch results incorrect");
        end

        $display("PASS: Vynox-128 ISA ALU, immediate, and signed/unsigned branches");
        $finish;
    end

    initial begin
        #10000;
        $fatal(1, "Timed out in Vynox-128 ISA test");
    end
endmodule
