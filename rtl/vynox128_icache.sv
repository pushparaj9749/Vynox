// Small direct-mapped, read-only instruction cache with a one-word line.
// Misses use a single outstanding memory request; invalidate drains an in-flight
// refill but prevents it from repopulating the cache.
`timescale 1ns/1ps
module vynox128_icache #(
    parameter integer LINES = 16
) (
    input  wire         clk,
    input  wire         reset,

    input  wire         cpu_valid,
    input  wire         cpu_cacheable,
    input  wire [63:0]  cpu_address,
    input  wire         invalidate,
    output logic        cpu_ready,
    output logic        cpu_error,
    output logic [63:0] cpu_instruction,

    output logic        memory_valid,
    output logic [63:0] memory_address,
    input  wire         memory_ready,
    input  wire         memory_error,
    input  wire [63:0]  memory_read_data
);
    localparam integer INDEX_BITS = $clog2(LINES);
    localparam integer TAG_BITS = 61 - INDEX_BITS;
    localparam logic STATE_IDLE = 1'b0;
    localparam logic STATE_MISS = 1'b1;

    logic state;
    logic [LINES-1:0] line_valid;
    logic [TAG_BITS-1:0] line_tag [0:LINES-1];
    logic [63:0] line_data [0:LINES-1];

    logic [63:0] request_address;
    logic [INDEX_BITS-1:0] request_index;
    logic [TAG_BITS-1:0] request_tag;
    logic discard_request;

    wire [INDEX_BITS-1:0] lookup_index = cpu_address[3 +: INDEX_BITS];
    wire [TAG_BITS-1:0] lookup_tag = cpu_address[63:3+INDEX_BITS];
    wire lookup_hit = line_valid[lookup_index] && (line_tag[lookup_index] == lookup_tag);

    integer line_index;

    initial begin
        if ((LINES < 2) || ((LINES & (LINES - 1)) != 0)) begin
            $fatal(1, "vynox128_icache LINES must be a power of two >= 2");
        end
    end

    always @* begin
        cpu_ready = 1'b0;
        cpu_error = 1'b0;
        cpu_instruction = 64'd0;
        memory_valid = (state == STATE_MISS);
        memory_address = request_address;

        if (cpu_valid) begin
            if (!cpu_cacheable) begin
                // This SoC has executable ROM only; non-cacheable fetches are
                // reported to the core as instruction access faults.
                cpu_ready = 1'b1;
                cpu_error = 1'b1;
            end else if (lookup_hit) begin
                cpu_ready = 1'b1;
                cpu_instruction = line_data[lookup_index];
            end else if ((state == STATE_MISS) &&
                         (cpu_address == request_address) && memory_ready) begin
                // Deliver the refill word on the accepting edge. If the PC was
                // redirected while the request was outstanding, do not leak a
                // stale response into the new instruction stream.
                cpu_ready = 1'b1;
                cpu_error = memory_error;
                cpu_instruction = memory_read_data;
            end
        end
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            state <= STATE_IDLE;
            request_address <= 64'd0;
            request_index <= '0;
            request_tag <= '0;
            discard_request <= 1'b0;
            line_valid <= '0;
            for (line_index = 0; line_index < LINES; line_index = line_index + 1) begin
                line_tag[line_index] <= '0;
                line_data[line_index] <= 64'd0;
            end
        end else begin
            if (invalidate) begin
                line_valid <= '0;
                if ((state == STATE_MISS) && !memory_ready) begin
                    discard_request <= 1'b1;
                end else begin
                    discard_request <= 1'b0;
                end
            end

            case (state)
                STATE_IDLE: begin
                    if (!invalidate && cpu_valid && cpu_cacheable && !lookup_hit) begin
                        request_address <= cpu_address;
                        request_index <= lookup_index;
                        request_tag <= lookup_tag;
                        discard_request <= 1'b0;
                        state <= STATE_MISS;
                    end
                end

                STATE_MISS: begin
                    if (memory_ready) begin
                        if (!memory_error && !discard_request && !invalidate) begin
                            line_valid[request_index] <= 1'b1;
                            line_tag[request_index] <= request_tag;
                            line_data[request_index] <= memory_read_data;
                        end
                        state <= STATE_IDLE;
                        discard_request <= 1'b0;
                    end
                end

                default: begin
                    state <= STATE_IDLE;
                    discard_request <= 1'b0;
                end
            endcase
        end
    end
endmodule
