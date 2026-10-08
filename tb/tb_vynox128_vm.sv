`timescale 1ns/1ps

module tb_vynox128_vm;
    localparam logic [63:0] USER_TEXT_VA = 64'h0000_0000_0040_0080;
    localparam logic [63:0] USER_DATA_VA = 64'h0000_0000_0040_2100;
    localparam logic [63:0] UNMAPPED_DATA_VA = 64'h0000_0000_0060_0000;
    localparam logic [63:0] SATP_VALUE = 64'h9000_0000_0000_0001;

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

    function automatic logic [63:0] pte_table(input logic [39:0] ppn);
        pte_table = {12'd0, ppn, 12'd0} | 64'd1;
    endfunction

    function automatic logic [63:0] pte_user_leaf(input logic [39:0] ppn);
        // V,R,W,X,U,A,D; software has initialized A and D.
        pte_user_leaf = {12'd0, ppn, 12'd0} | 64'h7F;
    endfunction

    vynox128_soc #(
        .PROGRAM_FILE("build/vm_trap_user.hex"),
        .MEMORY_WAIT_CYCLES(2),
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
        end else begin
            if (dut.instruction_ptw_valid && !dut.ptw_busy) begin
                instruction_ptw_requests <= instruction_ptw_requests + 1;
            end
            if (dut.data_ptw_valid && !dut.ptw_busy) begin
                data_ptw_requests <= data_ptw_requests + 1;
            end
        end
    end

    task automatic initialize_page_tables;
        begin
            // VA 0x0040_0000 and 0x0040_2000 share the first three levels.
            // Root PPN 1 -> PPN 2 -> PPN 3 -> PPN 4.
            dut.data_ram[256][63:0] = pte_table(40'd2);   // 0x1000, VPN[3]=0
            dut.data_ram[512][63:0] = pte_table(40'd3);   // 0x2000, VPN[2]=0
            dut.data_ram[769][63:0] = pte_table(40'd4);   // 0x3010, VPN[1]=2
            dut.data_ram[1024][63:0] = pte_user_leaf(40'd0); // VA text page -> PA 0
            dut.data_ram[1025][63:0] = pte_user_leaf(40'd0); // VA data page -> PA 0
            // VPN[1]=3 remains invalid for the page-fault test.
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        gpio_in = 128'd0;
        uart_rx = 1'b1;
        instruction_ptw_requests = 0;
        data_ptw_requests = 0;

        // Allow the SoC's zero-fill/readmemh initialization to finish first.
        #1;
        initialize_page_tables();
        repeat (2) @(negedge clk);
        reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);

        if (fault !== 1'b1 || fault_code !== 8'h08) begin
            $fatal(1, "Expected user load page fault (fault=%b code=%02h)", fault, fault_code);
        end
        if (gpio_out !== 128'h5A || dut.data_ram[16] !== 128'h5A) begin
            $fatal(1, "Translated U-mode store/load failed (GPIO=%h RAM=%h)",
                   gpio_out, dut.data_ram[16]);
        end
        if (dut.core.satp !== SATP_VALUE || dut.core.registers[8] !== SATP_VALUE) begin
            $fatal(1, "SATP write/readback mismatch: CSR=%h R8=%h", dut.core.satp, dut.core.registers[8]);
        end
        if (dut.core.mcause !== 64'd13 || dut.core.mepc !== 64'h0040_00B0 ||
            dut.core.mtval !== UNMAPPED_DATA_VA) begin
            $fatal(1, "Load page-fault CSRs incorrect: cause=%h mepc=%h mtval=%h",
                   dut.core.mcause, dut.core.mepc, dut.core.mtval);
        end
        if (instruction_ptw_requests != 4 || data_ptw_requests != 7) begin
            $fatal(1, "Unexpected shared PTW traffic: instruction=%0d data=%0d",
                   instruction_ptw_requests, data_ptw_requests);
        end
        if (dut.core.privilege_mode !== 2'b11) begin
            $fatal(1, "Page-fault trap did not return to machine mode");
        end

        // Clear the executable leaf and restart: the very first U-mode fetch
        // must raise an instruction-page fault carrying its original VA.
        @(negedge clk);
        reset = 1'b1;
        dut.data_ram[1024][63:0] = 64'd0;
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
        if (gpio_out !== 128'd0) begin
            $fatal(1, "Unmapped user text executed unexpectedly (GPIO=%h)", gpio_out);
        end

        // A valid non-leaf PTE that points beyond physical RAM must become an
        // instruction/load access fault, distinct from a page fault.
        @(negedge clk);
        reset = 1'b1;
        initialize_page_tables();
        dut.data_ram[769][127:64] = pte_table(40'd8); // VPN[1]=3 -> 0x8000 (out of RAM)
        repeat (2) @(negedge clk);
        reset = 1'b0;

        wait (halted === 1'b1);
        @(negedge clk);
        if (fault !== 1'b1 || fault_code !== 8'h03 ||
            dut.core.mcause !== 64'd5 || dut.core.mepc !== 64'h0040_00B0 ||
            dut.core.mtval !== UNMAPPED_DATA_VA) begin
            $fatal(1, "Page-table bus fault metadata incorrect: code=%h cause=%h mepc=%h mtval=%h",
                   fault_code, dut.core.mcause, dut.core.mepc, dut.core.mtval);
        end
        if (gpio_out !== 128'h5A) begin
            $fatal(1, "Successful translated access was lost before PTW bus fault (GPIO=%h)", gpio_out);
        end

        $display("PASS: integrated I/D translation, SATP/SFENCE, PTW errors, and virtual MTVAL");
        $finish;
    end

    initial begin
        #200000;
        $fatal(1, "Timed out in integrated virtual-memory tests");
    end
endmodule
