`timescale 1ns/1ps

module tb_vynox128_mmu;
    localparam logic [63:0] VA_PAGE1 = 64'h0000_0000_4000_1234;
    localparam logic [63:0] VA_PAGE2 = 64'h0000_0000_4000_2234;
    localparam logic [63:0] VA_PAGE3 = 64'h0000_0000_4000_3234;
    localparam logic [63:0] VA_PAGE4 = 64'h0000_0000_4000_4234;
    localparam logic [63:0] PTW_ERROR_ADDRESS = 64'h8000;
    localparam logic [1:0] ACCESS_READ = 2'd0;
    localparam logic [1:0] ACCESS_WRITE = 2'd1;
    localparam logic [1:0] ACCESS_EXECUTE = 2'd2;

    logic clk;
    logic reset;
    logic request_valid;
    logic [63:0] virtual_address;
    logic [1:0] privilege_mode;
    logic [1:0] access_type;
    logic vm_enable;
    logic [39:0] root_ppn;
    logic tlb_flush;
    wire request_ready;
    wire [63:0] physical_address;
    wire request_fault;
    wire [63:0] fault_cause;
    wire [63:0] fault_address;
    wire ptw_valid;
    wire [63:0] ptw_address;
    logic ptw_busy;
    logic [2:0] ptw_delay;
    logic [63:0] ptw_pending_address;
    logic [63:0] page_table_memory [0:8191];
    integer page_walk_reads;
    integer i;

    wire ptw_ready = ptw_busy && (ptw_delay == 3'd0);
    wire ptw_error = ptw_ready && (ptw_pending_address == PTW_ERROR_ADDRESS);
    wire [63:0] ptw_read_data = page_table_memory[ptw_pending_address[15:3]];

    function automatic logic [63:0] pte_table(input logic [39:0] ppn);
        pte_table = {12'd0, ppn, 12'd0} | 64'h1;
    endfunction

    function automatic logic [63:0] pte_leaf(
        input logic [39:0] ppn,
        input logic [11:0] flags
    );
        pte_leaf = {12'd0, ppn, 12'd0} | flags;
    endfunction

    vynox128_mmu #(.TLB_ENTRIES(4)) dut (
        .clk(clk), .reset(reset),
        .request_valid(request_valid), .virtual_address(virtual_address),
        .privilege_mode(privilege_mode), .access_type(access_type),
        .vm_enable(vm_enable), .root_ppn(root_ppn), .tlb_flush(tlb_flush),
        .request_ready(request_ready), .physical_address(physical_address),
        .request_fault(request_fault), .fault_cause(fault_cause),
        .fault_address(fault_address),
        .ptw_valid(ptw_valid), .ptw_address(ptw_address),
        .ptw_ready(ptw_ready), .ptw_error(ptw_error), .ptw_read_data(ptw_read_data)
    );

    always #5 clk = ~clk;

    always @(posedge clk) begin
        if (reset) begin
            ptw_busy <= 1'b0;
            ptw_delay <= 3'd0;
            ptw_pending_address <= 64'd0;
            page_walk_reads <= 0;
        end else if (!ptw_busy && ptw_valid) begin
            ptw_busy <= 1'b1;
            ptw_delay <= 3'd2;
            ptw_pending_address <= ptw_address;
            page_walk_reads <= page_walk_reads + 1;
        end else if (ptw_busy) begin
            if (ptw_delay != 3'd0) begin
                ptw_delay <= ptw_delay - 3'd1;
            end else begin
                ptw_busy <= 1'b0;
            end
        end
    end

    task automatic translate_expect(
        input logic [63:0] address_value,
        input logic [1:0] access_value,
        input logic expected_fault,
        input logic [63:0] expected_physical,
        input logic [63:0] expected_cause
    );
        integer cycles;
        begin
            @(negedge clk);
            request_valid = 1'b1;
            virtual_address = address_value;
            access_type = access_value;
            cycles = 0;
            while ((request_ready !== 1'b1) && (cycles < 300)) begin
                @(negedge clk);
                cycles = cycles + 1;
            end
            if (request_ready !== 1'b1) begin
                $fatal(1, "MMU translation timed out for VA %h", address_value);
            end
            if (request_fault !== expected_fault) begin
                $fatal(1, "MMU fault mismatch for VA %h (got %b cause %0d)",
                       address_value, request_fault, fault_cause);
            end
            if (expected_fault) begin
                if (fault_cause !== expected_cause || fault_address !== address_value) begin
                    $fatal(1, "MMU fault metadata mismatch (cause=%0d mtval=%h)",
                           fault_cause, fault_address);
                end
            end else if (physical_address !== expected_physical) begin
                $fatal(1, "MMU physical address mismatch (got %h expected %h)",
                       physical_address, expected_physical);
            end
            @(posedge clk);
            #1;
            @(negedge clk);
            request_valid = 1'b0;
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        request_valid = 1'b0;
        virtual_address = 64'd0;
        privilege_mode = 2'b00;
        access_type = ACCESS_READ;
        vm_enable = 1'b0;
        root_ppn = 40'd1;
        tlb_flush = 1'b0;
        for (i = 0; i < 8192; i = i + 1) page_table_memory[i] = 64'd0;

        // Four-level mapping for VA 0x4000_1234 -> PA 0x0000_5234.
        page_table_memory[512]  = pte_table(40'd2); // root PPN 1, L3 index 0
        page_table_memory[1025] = pte_table(40'd3); // L2 PPN 2, index 1
        page_table_memory[1536] = pte_table(40'd4); // L1 PPN 3, index 0
        page_table_memory[2049] = pte_leaf(40'd5, 12'h07F); // L0 PPN 4, index 1: RWXUAD
        page_table_memory[2050] = pte_leaf(40'd6, 12'h06F); // index 2: supervisor-only
        page_table_memory[2051] = pte_leaf(40'd7, 12'h03B); // index 3: no write permission
        page_table_memory[2052] = 64'd0; // index 4: invalid

        repeat (2) @(negedge clk);
        reset = 1'b0;

        // Bare mode and machine mode bypass page translation.
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b0, VA_PAGE1, 64'd0);
        vm_enable = 1'b1;
        privilege_mode = 2'b11;
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b0, VA_PAGE1, 64'd0);
        privilege_mode = 2'b00;

        // First access walks four levels; subsequent access types hit the TLB.
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b0, 64'h5234, 64'd0);
        if (page_walk_reads != 4) $fatal(1, "Expected four PTE reads for a cold Sv48 walk");
        translate_expect(VA_PAGE1, ACCESS_WRITE, 1'b0, 64'h5234, 64'd0);
        translate_expect(VA_PAGE1, ACCESS_EXECUTE, 1'b0, 64'h5234, 64'd0);
        if (page_walk_reads != 4) $fatal(1, "TLB hit unexpectedly launched a page walk");

        // Invalid permissions and invalid PTEs produce access-specific page faults.
        translate_expect(VA_PAGE2, ACCESS_READ, 1'b1, 64'd0, 64'd13);
        translate_expect(VA_PAGE3, ACCESS_WRITE, 1'b1, 64'd0, 64'd15);
        translate_expect(VA_PAGE4, ACCESS_READ, 1'b1, 64'd0, 64'd13);
        translate_expect(64'h0001_0000_0000_0000, ACCESS_READ, 1'b1, 64'd0, 64'd13);

        // SFENCE-style invalidation forces a new walk and observes updated PTE data.
        page_table_memory[2049] = pte_leaf(40'd9, 12'h07F);
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b0, 64'h5234, 64'd0);
        @(negedge clk);
        tlb_flush = 1'b1;
        @(posedge clk);
        #1 tlb_flush = 1'b0;
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b0, 64'h9234, 64'd0);
        if (page_walk_reads != 20) $fatal(1, "TLB flush did not force a four-level refill");

        // A redirected/bare request must not consume an older walk's later error.
        root_ppn = 40'd8;
        @(negedge clk);
        request_valid = 1'b1;
        virtual_address = VA_PAGE1;
        access_type = ACCESS_READ;
        vm_enable = 1'b1;
        wait (ptw_valid === 1'b1);
        @(negedge clk);
        vm_enable = 1'b0;
        virtual_address = 64'h1234;
        #1;
        if (request_ready !== 1'b1 || request_fault !== 1'b0 ||
            physical_address !== 64'h1234) begin
            $fatal(1, "Bare redirected request consumed a stale page-walk response");
        end
        @(posedge clk);
        #1;
        @(negedge clk);
        request_valid = 1'b0;
        wait (ptw_busy === 1'b0);
        repeat (2) @(negedge clk);

        // Page-table bus failures map to access-fault causes, not page faults.
        vm_enable = 1'b1;
        translate_expect(VA_PAGE1, ACCESS_READ, 1'b1, 64'd0, 64'd5);

        $display("PASS: 4-level translation, TLB, permissions, page/access faults, and flush");
        $finish;
    end

    initial begin
        #100000;
        $fatal(1, "Timed out in standalone MMU tests");
    end
endmodule
