`timescale 1ns/1ps

module tb_vynox128_vm;
    localparam logic [63:0] USER_TEXT_VA = 64'h0000_0000_0040_0300;
    localparam logic [63:0] USER_DATA_VA = 64'h0000_0000_0040_2100;
    localparam integer USER_DATA_OFFSET = USER_DATA_VA & 64'h0000_0000_0000_0FFF;
    localparam integer OLD_DATA_RAM_WORD = USER_DATA_OFFSET >> 4;
    localparam integer REMAPPED_DATA_RAM_WORD = (32'h5000 + USER_DATA_OFFSET) >> 4;
    localparam logic [63:0] PAGE_FAULT_DATA_VA = 64'h0000_0000_0040_3000;
    localparam logic [63:0] PTW_FAULT_DATA_VA = 64'h0000_0000_0060_0000;
    localparam logic [63:0] SATP_VALUE = 64'h9000_0000_0000_0001;
    localparam integer CODE_LEAF_LI_INDEX = 24;
    localparam integer USER_BAD_VA_LI_INDEX = 106;
    localparam logic [7:0] OP_ADDI = 8'h0B;

    logic clk;
    logic reset;
    logic [127:0] gpio_in;
    logic uart_rx;
    wire uart_tx;
    wire [127:0] gpio_out;
    wire timer_irq;
    wire halted;
    wire fault;
    wire [7:0] fault_code;
    integer instruction_ptw_requests;
    integer data_ptw_requests;
    integer mmu_flush_pulses;
    logic [63:0] original_code_leaf_li;
    logic [63:0] original_bad_va_li;

    function automatic logic [63:0] encode_i(
        input logic [7:0] opcode,
        input logic [4:0] rd,
        input logic [4:0] rs1,
        input logic [31:0] immediate
    );
        encode_i = {opcode, rd, rs1, 5'd0, immediate, 9'd0};
    endfunction

    vynox128_soc #(
        .PROGRAM_FILE("build/vm_trap_user.hex"),
        .MEMORY_WAIT_CYCLES(2),
        .USER_RAM_LIMIT(64'h0000_0000_0000_6000),
        .INSTRUCTION_WAIT_CYCLES(1)
    ) dut (
        .clk(clk), .reset(reset), .gpio_in(gpio_in), .uart_rx(uart_rx),
        .uart_tx(uart_tx), .gpio_out(gpio_out), .timer_irq(timer_irq),
        .halted(halted), .fault(fault), .fault_code(fault_code)
    );

    always #5 clk = ~clk;

    always @(posedge clk) begin
        if (reset) begin
            instruction_ptw_requests <= 0;
            data_ptw_requests <= 0;
            mmu_flush_pulses <= 0;
        end else begin
            if (dut.instruction_ptw_valid && !dut.ptw_busy) begin
                instruction_ptw_requests <= instruction_ptw_requests + 1;
            end
            if (dut.data_ptw_valid && !dut.ptw_busy) begin
                data_ptw_requests <= data_ptw_requests + 1;
            end
            if (dut.core_mmu_flush) mmu_flush_pulses <= mmu_flush_pulses + 1;
        end
    end

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        instruction_ptw_requests = 0;
        data_ptw_requests = 0;
        mmu_flush_pulses = 0;
        original_code_leaf_li = 64'd0;
        original_bad_va_li = 64'd0;

        // The software boot program constructs every page-table level with
        // ordinary M-mode 128-bit stores; the testbench does not seed PTEs.
        #1;
        original_code_leaf_li = dut.instruction_rom[CODE_LEAF_LI_INDEX];
        original_bad_va_li = dut.instruction_rom[USER_BAD_VA_LI_INDEX];
        repeat (2) @(negedge clk);
        reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b1 || fault_code !== 8'h08) begin
            $fatal(1, "Expected user load page fault (fault=%b code=%02h)", fault, fault_code);
        end
        if (gpio_out !== 128'hA5 || dut.data_ram[OLD_DATA_RAM_WORD] !== 128'h5A ||
            dut.data_ram[REMAPPED_DATA_RAM_WORD] !== 128'hA5) begin
            $fatal(1, "Translated remap/SFENCE access failed (GPIO=%h old=%h new=%h)",
                   gpio_out, dut.data_ram[OLD_DATA_RAM_WORD], dut.data_ram[REMAPPED_DATA_RAM_WORD]);
        end
        if (dut.core.satp !== SATP_VALUE || dut.core.registers[8] !== SATP_VALUE) begin
            $fatal(1, "SATP write/readback mismatch: CSR=%h R8=%h", dut.core.satp, dut.core.registers[8]);
        end
        if (dut.core.mcause !== 64'd13 || dut.core.mepc !== USER_TEXT_VA + 64'h58 ||
            dut.core.mtval !== PAGE_FAULT_DATA_VA) begin
            $fatal(1, "Load page-fault CSRs incorrect: cause=%h mepc=%h mtval=%h",
                   dut.core.mcause, dut.core.mepc, dut.core.mtval);
        end
        // Check that the CPU itself populated the four levels and both leaves.
        if (dut.data_ram[256][63:0] !== 64'h2001 ||
            dut.data_ram[512][63:0] !== 64'h3001 ||
            dut.data_ram[769][63:0] !== 64'h4001 ||
            dut.data_ram[769][127:64] !== 64'h8001 ||
            dut.data_ram[1024][63:0] !== 64'h007F ||
            dut.data_ram[1025][63:0] !== 64'h507F) begin
            $fatal(1, "M-mode software did not construct the expected PTE hierarchy");
        end
        if (instruction_ptw_requests != 8 || data_ptw_requests != 12 || mmu_flush_pulses < 3) begin
            $fatal(1, "Unexpected PTW/flush traffic: I=%0d D=%0d flush=%0d",
                   instruction_ptw_requests, data_ptw_requests, mmu_flush_pulses);
        end
        if (dut.core.privilege_mode !== 2'b11) begin
            $fatal(1, "Page-fault trap did not return to machine mode");
        end

        // Change the boot instruction that materializes the executable leaf.
        // On restart, software writes an invalid PTE and the first U fetch faults.
        @(negedge clk);
        reset = 1'b1;
        dut.instruction_rom[CODE_LEAF_LI_INDEX] = encode_i(OP_ADDI, 5'd10, 5'd0, 32'd0);
        repeat (2) @(negedge clk);
        reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h08 ||
            dut.core.mcause !== 64'd12 || dut.core.mepc !== USER_TEXT_VA ||
            dut.core.mtval !== USER_TEXT_VA) begin
            $fatal(1, "Instruction page-fault metadata incorrect: code=%h cause=%h mepc=%h mtval=%h",
                   fault_code, dut.core.mcause, dut.core.mepc, dut.core.mtval);
        end
        if (dut.data_ram[1024][63:0] !== 64'd0 || gpio_out !== 128'd0) begin
            $fatal(1, "Software-built invalid code PTE was not observed");
        end

        // Restore executable mapping and redirect the user load through VPN[1]=3,
        // whose software-built pointer targets physical 0x8000, beyond 32 KiB RAM.
        @(negedge clk);
        reset = 1'b1;
        dut.instruction_rom[CODE_LEAF_LI_INDEX] = original_code_leaf_li;
        dut.instruction_rom[USER_BAD_VA_LI_INDEX] =
            encode_i(OP_ADDI, 5'd5, 5'd0, PTW_FAULT_DATA_VA[31:0]);
        repeat (2) @(negedge clk);
        reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h03 ||
            dut.core.mcause !== 64'd5 || dut.core.mepc !== USER_TEXT_VA + 64'h58 ||
            dut.core.mtval !== PTW_FAULT_DATA_VA) begin
            $fatal(1, "PTW access-fault metadata incorrect: code=%h cause=%h mepc=%h mtval=%h",
                   fault_code, dut.core.mcause, dut.core.mepc, dut.core.mtval);
        end
        if (gpio_out !== 128'hA5 || dut.data_ram[OLD_DATA_RAM_WORD] !== 128'h5A ||
            dut.data_ram[REMAPPED_DATA_RAM_WORD] !== 128'hA5) begin
            $fatal(1, "SFENCE remap did not reach the new physical page before PTW fault");
        end
        if (original_bad_va_li[63:56] !== OP_ADDI ||
            original_bad_va_li[40:9] !== 32'h00403000) begin
            $fatal(1, "Test program layout changed: expected LI R5, 0x00403000");
        end

        $display("PASS: software-built page tables, I/D translation, SATP/SFENCE, PTW faults, and virtual MTVAL");
        $finish;
    end

    initial begin
        #300000;
        $fatal(1, "Timed out in integrated virtual-memory tests");
    end
endmodule
