`timescale 1ns/1ps

module tb_vynox128_faults;
    localparam logic [7:0] OP_ADDI = 8'h0B;
    localparam logic [7:0] OP_LOAD = 8'h10;
    localparam logic [7:0] OP_JAL  = 8'h30;
    localparam logic [7:0] OP_HALT = 8'hFF;

    logic clk;
    logic reset;
    logic [63:0] instruction;
    wire [63:0] instruction_address;
    wire [63:0] ex_data_address;
    wire instruction_error;
    wire data_valid;
    wire data_write_enable;
    wire [63:0] data_address;
    wire [127:0] data_write_data;
    wire [127:0] data_read_data;
    logic bus_error_force;
    wire [127:0] gpio_out;
    wire halted;
    wire fault;
    wire [7:0] fault_code;
    logic [63:0] rom [0:255];
    integer i;

    function automatic logic [63:0] encode_addi(
        input logic [4:0] rd, input logic [4:0] rs1, input logic [31:0] imm
    );
        encode_addi = {OP_ADDI, rd, rs1, 5'd0, imm, 9'd0};
    endfunction

    function automatic logic [63:0] encode_load(input logic [4:0] rd, input logic [31:0] imm);
        encode_load = {OP_LOAD, rd, 5'd0, 5'd0, imm, 9'd0};
    endfunction

    function automatic logic [63:0] encode_jal(input logic [4:0] rd, input logic [31:0] imm);
        encode_jal = {OP_JAL, rd, 5'd0, 5'd0, imm, 9'd0};
    endfunction

    assign data_read_data = 128'd0;
    assign instruction_error = (instruction_address[63:11] != 53'd0);

    vynox128_core dut (
        .clk(clk), .reset(reset), .irq(1'b0), .instruction(instruction),
        .instruction_ready(1'b1),
        .instruction_error(instruction_error), .instruction_address(instruction_address),
        .instruction_fault_cause(64'd1), .instruction_fault_address(instruction_address),
        .mmu_flush(), .satp_value(),
        .data_valid(data_valid),
        .data_write_enable(data_write_enable), .data_address(data_address),
        .data_write_data(data_write_data),
        .ex_data_address(ex_data_address), .data_access_allowed(1'b1), .data_translation_ready(1'b1),
        .data_translation_address(ex_data_address), .data_translation_fault(1'b0),
        .data_translation_fault_cause(64'd0),
        .data_translation_fault_address(ex_data_address), .data_ready(1'b1),
        .data_error(bus_error_force), .data_read_data(data_read_data),
        .gpio_in(128'd0), .gpio_out(gpio_out), .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;
    always @* instruction = rom[instruction_address[10:3]];

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        bus_error_force = 1'b0;
        for (i = 0; i < 256; i = i + 1) rom[i] = 64'd0;

        // The misaligned access faults precisely: older dependent instructions
        // retire, but the younger wrong-path write never reaches the register file.
        rom[0] = encode_addi(5'd5, 5'd0, 32'd7);
        rom[1] = encode_addi(5'd6, 5'd5, 32'd1);
        rom[2] = encode_load(5'd1, 32'd1);
        rom[3] = encode_addi(5'd7, 5'd0, 32'd99);
        rom[32] = {OP_HALT, 56'd0}; // trap handler terminates each directed fault case
        #17 reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h02 || data_valid !== 1'b0) begin
            $fatal(1, "Misaligned access did not report alignment fault (code=%02h)", fault_code);
        end
        if (dut.registers[5] !== 128'd7 || dut.registers[6] !== 128'd8 ||
            dut.registers[7] !== 128'd0) begin
            $fatal(1, "Synchronous alignment fault was not precise");
        end
        if (dut.mepc !== 64'h10 || dut.mcause !== 64'd4 || dut.mtval !== 64'd1) begin
            $fatal(1, "Load-misalignment trap CSRs are incorrect");
        end

        // Reset, then model an aligned access rejected by the memory system.
        reset = 1'b1;
        bus_error_force = 1'b1;
        rom[0] = encode_load(5'd1, 32'h00000100);
        @(negedge clk);
        reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h03) begin
            $fatal(1, "Memory bus error did not report data-bus fault (code=%02h)", fault_code);
        end
        if (dut.mepc !== 64'd0 || dut.mcause !== 64'd5 || dut.mtval !== 64'h100) begin
            $fatal(1, "Load-access trap CSRs are incorrect");
        end

        // A jump outside the 2 KiB ROM window must report a fetch-range fault.
        reset = 1'b1;
        bus_error_force = 1'b0;
        rom[0] = encode_jal(5'd0, 32'd256);
        @(negedge clk);
        reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h04) begin
            $fatal(1, "Out-of-range fetch did not report fault (code=%02h)", fault_code);
        end
        if (dut.mepc !== 64'h800 || dut.mcause !== 64'd1 || dut.mtval !== 64'h800) begin
            $fatal(1, "Instruction-access trap CSRs are incorrect");
        end

        $display("PASS: Vynox-128 alignment, bus, and fetch-range fault handling");
        $finish;
    end

    initial begin
        #5000;
        $fatal(1, "Timed out in Vynox-128 fault tests");
    end
endmodule
