`timescale 1ns/1ps

module tb_vynox128_dcache;
    logic clk;
    logic reset;
    logic cpu_valid;
    logic cpu_write;
    logic [63:0] cpu_address;
    logic [127:0] cpu_write_data;
    logic cpu_cacheable;
    wire cpu_ready;
    wire cpu_error;
    wire [127:0] cpu_read_data;
    wire memory_valid;
    wire memory_write;
    wire [63:0] memory_address;
    wire [127:0] memory_write_data;
    logic memory_ready;
    logic memory_error;
    logic [127:0] memory_read_data;

    logic memory_busy;
    integer memory_wait;
    integer backing_transactions;
    logic [63:0] saved_address;
    logic saved_write;
    logic [127:0] saved_write_data;
    logic [127:0] backing_memory [0:255];
    integer i;
    integer latency;
    integer transactions_before;

    vynox128_dcache #(.LINES(16)) dut (
        .clk(clk), .reset(reset),
        .cpu_valid(cpu_valid), .cpu_write(cpu_write),
        .cpu_address(cpu_address), .cpu_write_data(cpu_write_data),
        .cpu_cacheable(cpu_cacheable), .cpu_ready(cpu_ready),
        .cpu_error(cpu_error), .cpu_read_data(cpu_read_data),
        .memory_valid(memory_valid), .memory_write(memory_write),
        .memory_address(memory_address), .memory_write_data(memory_write_data),
        .memory_ready(memory_ready), .memory_error(memory_error),
        .memory_read_data(memory_read_data)
    );

    always #5 clk = ~clk;

    always @* begin
        memory_ready = memory_busy && (memory_wait == 0);
        memory_error = 1'b0;
        memory_read_data = backing_memory[memory_address[11:4]];
    end

    always @(posedge clk) begin
        if (reset) begin
            memory_busy <= 1'b0;
            memory_wait <= 0;
            backing_transactions <= 0;
            saved_address <= 64'd0;
            saved_write <= 1'b0;
            saved_write_data <= 128'd0;
            for (i = 0; i < 256; i = i + 1) backing_memory[i] <= 128'd0;
        end else if (!memory_busy && memory_valid) begin
            memory_busy <= 1'b1;
            memory_wait <= 2;
            saved_address <= memory_address;
            saved_write <= memory_write;
            saved_write_data <= memory_write_data;
            backing_transactions <= backing_transactions + 1;
        end else if (memory_busy && (memory_wait > 0)) begin
            memory_wait <= memory_wait - 1;
        end else if (memory_busy && memory_ready) begin
            if (saved_write) backing_memory[saved_address[11:4]] <= saved_write_data;
            memory_busy <= 1'b0;
        end
    end

    task automatic load_word(
        input logic [63:0] address,
        input logic [127:0] expected,
        input logic cacheable,
        output integer waited_cycles
    );
        begin
            @(negedge clk);
            cpu_valid = 1'b1;
            cpu_write = 1'b0;
            cpu_address = address;
            cpu_write_data = 128'd0;
            cpu_cacheable = cacheable;
            #1;
            waited_cycles = 0;
            while (cpu_ready !== 1'b1) begin
                @(negedge clk);
                waited_cycles = waited_cycles + 1;
                if (waited_cycles > 30) $fatal(1, "Timed out loading %h", address);
            end
            if (cpu_error !== 1'b0 || cpu_read_data !== expected) begin
                $fatal(1, "Read %h got %032h (error=%b), expected %032h",
                       address, cpu_read_data, cpu_error, expected);
            end
            @(posedge clk);
            cpu_valid = 1'b0;
            @(negedge clk);
        end
    endtask

    task automatic store_word(
        input logic [63:0] address,
        input logic [127:0] value,
        input logic cacheable,
        output integer waited_cycles
    );
        begin
            @(negedge clk);
            cpu_valid = 1'b1;
            cpu_write = 1'b1;
            cpu_address = address;
            cpu_write_data = value;
            cpu_cacheable = cacheable;
            #1;
            waited_cycles = 0;
            while (cpu_ready !== 1'b1) begin
                @(negedge clk);
                waited_cycles = waited_cycles + 1;
                if (waited_cycles > 30) $fatal(1, "Timed out storing %h", address);
            end
            if (cpu_error !== 1'b0) $fatal(1, "Write to %h errored", address);
            @(posedge clk);
            cpu_valid = 1'b0;
            @(negedge clk);
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        cpu_valid = 1'b0;
        cpu_write = 1'b0;
        cpu_address = 64'd0;
        cpu_write_data = 128'd0;
        cpu_cacheable = 1'b1;
        memory_busy = 1'b0;
        memory_wait = 0;
        backing_transactions = 0;
        saved_address = 64'd0;
        saved_write = 1'b0;
        saved_write_data = 128'd0;
        memory_error = 1'b0;
        memory_read_data = 128'd0;
        for (i = 0; i < 256; i = i + 1) backing_memory[i] = 128'd0;

        #17 reset = 1'b0;
        backing_memory[8'h10] = 128'h55;
        backing_memory[8'h20] = 128'hAA;
        backing_memory[8'hF8] = 128'h77;

        load_word(64'h100, 128'h55, 1'b1, latency);
        if (backing_transactions != 1) $fatal(1, "First read should miss once");
        transactions_before = backing_transactions;

        load_word(64'h100, 128'h55, 1'b1, latency);
        if (latency != 0 || backing_transactions != transactions_before) begin
            $fatal(1, "Second read did not hit in the direct-mapped cache");
        end

        store_word(64'h100, 128'hDEAD_BEEF, 1'b1, latency);
        if (backing_transactions != transactions_before + 1 ||
            backing_memory[8'h10] !== 128'hDEAD_BEEF) begin
            $fatal(1, "Write-through store did not update backing RAM");
        end
        transactions_before = backing_transactions;

        load_word(64'h100, 128'hDEAD_BEEF, 1'b1, latency);
        if (latency != 0 || backing_transactions != transactions_before) begin
            $fatal(1, "Cached store value was not visible on the following hit");
        end

        load_word(64'h200, 128'hAA, 1'b1, latency);
        if (backing_transactions != transactions_before + 1) begin
            $fatal(1, "Conflicting tag should cause a direct-mapped miss");
        end
        transactions_before = backing_transactions;
        load_word(64'h100, 128'hDEAD_BEEF, 1'b1, latency);
        if (backing_transactions != transactions_before + 1) begin
            $fatal(1, "Evicted line should miss and refill from write-through RAM");
        end

        transactions_before = backing_transactions;
        load_word(64'hF80, 128'h77, 1'b0, latency);
        if (backing_transactions != transactions_before + 1) begin
            $fatal(1, "Uncached MMIO request did not bypass the cache");
        end
        transactions_before = backing_transactions;
        load_word(64'hF80, 128'h77, 1'b0, latency);
        if (backing_transactions != transactions_before + 1) begin
            $fatal(1, "Uncached MMIO access was incorrectly cached");
        end

        $display("PASS: direct-mapped D-cache miss/hit, write-through, conflict, and MMIO bypass");
        $finish;
    end

    initial begin
        #30000;
        $fatal(1, "Timed out in Vynox-128 data-cache test");
    end
endmodule
