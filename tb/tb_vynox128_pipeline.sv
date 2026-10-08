`timescale 1ns/1ps

module tb_vynox128_pipeline;
    localparam logic [7:0] OP_ADD   = 8'h01;
    localparam logic [7:0] OP_ADDI  = 8'h0B;
    localparam logic [7:0] OP_LOAD  = 8'h10;
    localparam logic [7:0] OP_STORE = 8'h11;
    localparam logic [7:0] OP_BEQ   = 8'h20;
    localparam logic [7:0] OP_OUT   = 8'h41;
    localparam logic [7:0] OP_HALT  = 8'hFF;

    logic clk;
    logic reset;
    logic [63:0] instruction;
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
    logic bus_busy;
    integer bus_wait;
    logic [63:0] bus_address;
    logic bus_write;
    logic [127:0] bus_write_data;
    integer load_use_bubbles;
    integer taken_redirects;
    integer cycles;
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

    function automatic logic [63:0] enc_s(
        input logic [7:0] op, input logic [4:0] base,
        input logic [4:0] source, input logic [31:0] imm
    );
        enc_s = {op, 5'd0, base, source, imm, 9'd0};
    endfunction

    function automatic logic [63:0] enc_b(
        input logic [7:0] op, input logic [4:0] rs1,
        input logic [4:0] rs2, input logic [31:0] imm
    );
        enc_b = {op, 5'd0, rs1, rs2, imm, 9'd0};
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
        .data_translation_fault_address(ex_data_address), .data_ready(data_ready), .data_error(data_error), .data_read_data(data_read_data),
        .gpio_in(gpio_in), .gpio_out(gpio_out), .halted(halted),
        .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;
    always @* begin
        instruction = rom[instruction_address[10:3]];
        data_read_data = data_memory[data_address[11:4]];
        data_ready = data_valid && bus_busy && (bus_wait == 0);
    end

    always @(posedge clk) begin
        if (reset) begin
            bus_busy <= 1'b0;
            bus_wait <= 0;
            bus_address <= 64'd0;
            bus_write <= 1'b0;
            bus_write_data <= 128'd0;
            load_use_bubbles <= 0;
            taken_redirects <= 0;
            cycles <= 0;
            for (i = 0; i < 256; i = i + 1) data_memory[i] <= 128'd0;
        end else begin
            cycles <= cycles + 1;
            if (dut.load_use_stall && !dut.exmem_memory_wait) begin
                load_use_bubbles <= load_use_bubbles + 1;
            end
            if (dut.ex_redirect) taken_redirects <= taken_redirects + 1;

            if (!bus_busy && data_valid) begin
                bus_busy <= 1'b1;
                bus_wait <= 2;
                bus_address <= data_address;
                bus_write <= data_write_enable;
                bus_write_data <= data_write_data;
            end else if (bus_busy && (bus_wait > 0)) begin
                bus_wait <= bus_wait - 1;
            end else if (bus_busy && data_ready) begin
                if (bus_write) data_memory[bus_address[11:4]] <= bus_write_data;
                bus_busy <= 1'b0;
            end
        end
    end

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        data_error = 1'b0;
        gpio_in = 128'd0;
        for (i = 0; i < 256; i = i + 1) rom[i] = 64'd0;

        // Consecutive dependencies exercise EX/MEM and MEM/WB bypasses.
        // The load-use pair forces exactly one interlock after memory returns.
        // The taken branch must flush the wrong-path write to R5.
        rom[0] = enc_i(OP_ADDI, 5'd1, 5'd0, 32'd1);
        rom[1] = enc_r(OP_ADD, 5'd2, 5'd1, 5'd1);
        rom[2] = enc_s(OP_STORE, 5'd0, 5'd2, 32'h100);
        rom[3] = enc_i(OP_LOAD, 5'd3, 5'd0, 32'h100);
        rom[4] = enc_r(OP_ADD, 5'd4, 5'd3, 5'd2);
        rom[5] = {OP_OUT, 5'd4, 5'd0, 5'd0, 32'd0, 9'd0};
        rom[6] = enc_b(OP_BEQ, 5'd0, 5'd0, 32'd2);
        rom[7] = enc_i(OP_ADDI, 5'd5, 5'd0, 32'd99);
        rom[8] = {OP_HALT, 56'd0};

        #17 reset = 1'b0;
        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b0 || fault_code !== 8'h00) begin
            $fatal(1, "Pipeline faulted unexpectedly with code %02h", fault_code);
        end
        if (dut.registers[1] !== 128'd1 || dut.registers[2] !== 128'd2 ||
            dut.registers[3] !== 128'd2 || dut.registers[4] !== 128'd4) begin
            $fatal(1, "Pipeline forwarding/load-use result mismatch");
        end
        if (data_memory[8'h10] !== 128'd2) begin
            $fatal(1, "Store-data forwarding failed: memory=%032h", data_memory[8'h10]);
        end
        if (gpio_out !== 128'd4 || dut.registers[5] !== 128'd0) begin
            $fatal(1, "Pipeline OUT/branch flush failed");
        end
        if (load_use_bubbles != 1 || taken_redirects != 1) begin
            $fatal(1, "Expected one load-use bubble and one redirect; got %0d and %0d",
                   load_use_bubbles, taken_redirects);
        end

        $display("PASS: five-stage pipeline bypass, load-use interlock, wait states, and flush");
        $finish;
    end

    initial begin
        #10000;
        $fatal(1, "Timed out in Vynox-128 pipeline test");
    end
endmodule
