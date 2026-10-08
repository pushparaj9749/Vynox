`timescale 1ns/1ps

module tb_vynox128_icache;
    localparam logic [63:0] ERROR_ADDRESS = 64'h108;

    logic clk;
    logic reset;
    logic cpu_valid;
    logic cpu_cacheable;
    logic [63:0] cpu_address;
    logic invalidate;
    wire cpu_ready;
    wire cpu_error;
    wire [63:0] cpu_instruction;
    wire memory_valid;
    wire [63:0] memory_address;
    logic memory_busy;
    logic [2:0] memory_delay;
    logic [63:0] pending_address;
    logic [63:0] backing_memory [0:63];
    integer memory_transactions;
    integer i;

    wire memory_ready = memory_busy && (memory_delay == 3'd0);
    wire memory_error = memory_ready && (pending_address == ERROR_ADDRESS);
    wire [63:0] memory_read_data = backing_memory[pending_address[8:3]];

    vynox128_icache #(.LINES(2)) dut (
        .clk(clk), .reset(reset),
        .cpu_valid(cpu_valid), .cpu_cacheable(cpu_cacheable),
        .cpu_address(cpu_address), .invalidate(invalidate),
        .cpu_ready(cpu_ready), .cpu_error(cpu_error),
        .cpu_instruction(cpu_instruction),
        .memory_valid(memory_valid), .memory_address(memory_address),
        .memory_ready(memory_ready), .memory_error(memory_error),
        .memory_read_data(memory_read_data)
    );

    always #5 clk = ~clk;

    always @(posedge clk) begin
        if (reset) begin
            memory_busy <= 1'b0;
            memory_delay <= 3'd0;
            pending_address <= 64'd0;
            memory_transactions <= 0;
        end else if (!memory_busy && memory_valid) begin
            memory_busy <= 1'b1;
            memory_delay <= 3'd2;
            pending_address <= memory_address;
            memory_transactions <= memory_transactions + 1;
        end else if (memory_busy) begin
            if (memory_delay != 3'd0) begin
                memory_delay <= memory_delay - 3'd1;
            end else begin
                memory_busy <= 1'b0;
            end
        end
    end

    task automatic fetch_expect(
        input logic [63:0] address,
        input logic expected_error,
        input logic [63:0] expected_instruction
    );
        integer cycles;
        begin
            @(negedge clk);
            cpu_valid = 1'b1;
            cpu_cacheable = 1'b1;
            cpu_address = address;
            cycles = 0;
            while ((cpu_ready !== 1'b1) && (cycles < 80)) begin
                @(negedge clk);
                cycles = cycles + 1;
            end
            if (cpu_ready !== 1'b1) begin
                $fatal(1, "I-cache fetch timed out at %h", address);
            end
            if (cpu_error !== expected_error) begin
                $fatal(1, "I-cache error mismatch at %h (got %b)", address, cpu_error);
            end
            if (!expected_error && (cpu_instruction !== expected_instruction)) begin
                $fatal(1, "I-cache data mismatch at %h (got %h expected %h)",
                       address, cpu_instruction, expected_instruction);
            end
            @(posedge clk);
            #1;
            @(negedge clk);
            cpu_valid = 1'b0;
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        cpu_valid = 1'b0;
        cpu_cacheable = 1'b1;
        cpu_address = 64'd0;
        invalidate = 1'b0;
        for (i = 0; i < 64; i = i + 1) begin
            backing_memory[i] = 64'hA500_0000_0000_0000 + i;
        end

        repeat (2) @(negedge clk);
        reset = 1'b0;

        // Cold miss, hit, and direct-mapped conflict replacement.
        fetch_expect(64'h00, 1'b0, backing_memory[0]);
        if (memory_transactions != 1) $fatal(1, "Expected one cold-fill transaction");
        fetch_expect(64'h00, 1'b0, backing_memory[0]);
        if (memory_transactions != 1) $fatal(1, "Cache hit unexpectedly accessed backing memory");
        fetch_expect(64'h08, 1'b0, backing_memory[1]);
        fetch_expect(64'h10, 1'b0, backing_memory[2]); // conflicts with address 0
        fetch_expect(64'h00, 1'b0, backing_memory[0]);
        if (memory_transactions != 4) $fatal(1, "Conflict replacement behavior is incorrect");

        // Redirect to a different miss while the old request is outstanding.
        @(negedge clk);
        cpu_valid = 1'b1;
        cpu_address = 64'h20;
        wait (memory_valid === 1'b1);
        @(negedge clk);
        cpu_address = 64'h28;
        if (cpu_ready !== 1'b0) $fatal(1, "Stale refill was exposed after a fetch redirect");
        wait (cpu_ready === 1'b1);
        if (cpu_instruction !== backing_memory[5]) begin
            $fatal(1, "Redirected fetch received stale instruction %h", cpu_instruction);
        end
        @(posedge clk);
        #1;
        @(negedge clk);
        cpu_valid = 1'b0;
        if (memory_transactions != 6) $fatal(1, "Redirected miss did not fetch both requested words");

        // FENCE.I invalidates every cached instruction.
        backing_memory[1] = 64'hDEAD_BEEF_0123_4567;
        @(negedge clk);
        invalidate = 1'b1;
        @(posedge clk);
        #1 invalidate = 1'b0;
        fetch_expect(64'h08, 1'b0, backing_memory[1]);
        if (memory_transactions != 7) $fatal(1, "Invalidation did not force a refill");

        // Invalidation during a refill drains the old response but discards it.
        @(negedge clk);
        cpu_valid = 1'b1;
        cpu_address = 64'h18;
        wait (memory_valid === 1'b1);
        @(negedge clk);
        invalidate = 1'b1;
        cpu_valid = 1'b0;
        @(posedge clk);
        #1 invalidate = 1'b0;
        wait (memory_busy === 1'b0);
        repeat (2) @(negedge clk);
        fetch_expect(64'h18, 1'b0, backing_memory[3]);
        if (memory_transactions != 9) $fatal(1, "In-flight invalidation did not discard the refill");

        // A backing-memory error reaches the core and is not cached.
        fetch_expect(ERROR_ADDRESS, 1'b1, 64'd0);
        if (memory_transactions != 10) $fatal(1, "Expected an instruction access-error transaction");
        fetch_expect(ERROR_ADDRESS, 1'b1, 64'd0);
        if (memory_transactions != 11) $fatal(1, "Errored instruction was incorrectly cached");

        // The SoC's unmapped instruction region bypasses the cache as an error.
        @(negedge clk);
        cpu_valid = 1'b1;
        cpu_cacheable = 1'b0;
        cpu_address = 64'h800;
        #1;
        if (cpu_ready !== 1'b1 || cpu_error !== 1'b1) begin
            $fatal(1, "Non-cacheable instruction fetch did not report an access fault");
        end
        @(posedge clk);
        #1;
        if (memory_transactions != 11) $fatal(1, "Unmapped instruction fetch accessed backing memory");

        $display("PASS: I-cache miss/hit, conflict, FENCE.I invalidation, stale refill discard, and errors");
        $finish;
    end

    initial begin
        #20000;
        $fatal(1, "Timed out in I-cache test");
    end
endmodule
