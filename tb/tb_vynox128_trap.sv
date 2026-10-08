`timescale 1ns/1ps

module tb_vynox128_trap;
    logic clk;
    logic reset;
    logic [63:0] instruction;
    wire [63:0] instruction_address;
    wire [63:0] ex_data_address;
    wire data_valid;
    wire data_write_enable;
    wire [63:0] data_address;
    wire [127:0] data_write_data;
    wire [127:0] gpio_out;
    wire halted;
    wire fault;
    wire [7:0] fault_code;
    logic [63:0] rom [0:255];
    integer i;

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
        $readmemh("build/trap_user.hex", rom);

        #17 reset = 1'b0;
        wait (gpio_out === 128'hA5);
        repeat (3) @(negedge clk);

        if (halted !== 1'b0) $fatal(1, "User program should remain in its spin loop");
        if (fault !== 1'b1 || fault_code !== 8'h05) begin
            $fatal(1, "Expected final user ECALL exception (fault=%b code=%02h)", fault, fault_code);
        end
        if (dut.privilege_mode !== 2'b00 || dut.in_interrupt !== 1'b0) begin
            $fatal(1, "MRET did not restore user privilege");
        end
        if (dut.mcause !== 64'd8 || dut.mepc !== 64'h98 || dut.mtval !== 64'd0) begin
            $fatal(1, "ECALL machine trap CSRs are incorrect: mcause=%h mepc=%h mtval=%h fault=%b/%h gpio=%h", dut.mcause, dut.mepc, dut.mtval, fault, fault_code, gpio_out);
        end
        if (dut.registers[8] !== 128'h100 || dut.registers[9] === 128'd0 ||
            dut.registers[14] === 128'd0 || dut.registers[10] !== 128'd8 ||
            dut.registers[11] !== 128'h98 || dut.registers[12] !== 128'd2) begin
            $fatal(1, "CSR/trap state mismatch: r8=%h r9=%h r14=%h r10=%h r11=%h r12=%h", dut.registers[8], dut.registers[9], dut.registers[14], dut.registers[10], dut.registers[11], dut.registers[12]);
        end
        if (dut.mstatus[12:11] !== 2'b00 || dut.mstatus[3] !== 1'b0 ||
            dut.mstatus[7] !== 1'b1) begin
            $fatal(1, "MSTATUS trap-entry/return fields are incorrect");
        end

        $display("PASS: machine CSRs, U-mode protection, ECALL trap, and MRET return");
        $finish;
    end

    initial begin
        #20000;
        $fatal(1, "Timed out in privilege and trap test");
    end
endmodule
