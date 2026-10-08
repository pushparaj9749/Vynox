// Small direct-mapped, write-through/write-allocate 128-bit data cache.
// The SoC marks RAM requests cacheable and bypasses all MMIO/unmapped accesses.
`timescale 1ns/1ps
module vynox128_dcache #(
    parameter integer LINES = 16
) (
    input  wire          clk,
    input  wire          reset,

    input  wire          cpu_valid,
    input  wire          cpu_write,
    input  wire [63:0]   cpu_address,
    input  wire [127:0]  cpu_write_data,
    input  wire          cpu_cacheable,
    output logic         cpu_ready,
    output logic         cpu_error,
    output logic [127:0] cpu_read_data,

    output logic         memory_valid,
    output logic         memory_write,
    output logic [63:0]  memory_address,
    output logic [127:0] memory_write_data,
    input  wire          memory_ready,
    input  wire          memory_error,
    input  wire [127:0]  memory_read_data
);
    localparam integer INDEX_BITS = $clog2(LINES);
    localparam integer TAG_BITS = 60 - INDEX_BITS;
    localparam logic [1:0] STATE_IDLE   = 2'd0;
    localparam logic [1:0] STATE_MISS   = 2'd1;
    localparam logic [1:0] STATE_WRITE  = 2'd2;
    localparam logic [1:0] STATE_BYPASS = 2'd3;

    logic [1:0] state;
    logic [LINES-1:0] line_valid;
    logic [TAG_BITS-1:0] line_tag [0:LINES-1];
    logic [127:0] line_data [0:LINES-1];

    logic [63:0] request_address;
    logic [127:0] request_write_data;
    logic request_write;
    logic [INDEX_BITS-1:0] request_index;
    logic [TAG_BITS-1:0] request_tag;

    wire [INDEX_BITS-1:0] lookup_index = cpu_address[4 +: INDEX_BITS];
    wire [TAG_BITS-1:0] lookup_tag = cpu_address[63:4+INDEX_BITS];
    wire lookup_hit = line_valid[lookup_index] && (line_tag[lookup_index] == lookup_tag);

    integer line_index;

    initial begin
        if ((LINES < 2) || ((LINES & (LINES - 1)) != 0)) begin
            $fatal(1, "vynox128_dcache LINES must be a power of two >= 2");
        end
    end

    always @* begin
        cpu_ready = 1'b0;
        cpu_error = 1'b0;
        cpu_read_data = 128'd0;
        memory_valid = 1'b0;
        memory_write = 1'b0;
        memory_address = request_address;
        memory_write_data = request_write_data;

        case (state)
            STATE_IDLE: begin
                if (cpu_valid && cpu_cacheable && !cpu_write && lookup_hit) begin
                    cpu_ready = 1'b1;
                    cpu_read_data = line_data[lookup_index];
                end
            end

            STATE_BYPASS: begin
                // Uncached MMIO is still a latched, single-outstanding request.
                memory_valid = 1'b1;
                memory_write = request_write;
                memory_address = request_address;
                memory_write_data = request_write_data;
                cpu_ready = memory_ready;
                cpu_error = memory_error;
                cpu_read_data = memory_read_data;
            end

            STATE_MISS: begin
                memory_valid = 1'b1;
                memory_address = request_address;
                cpu_ready = memory_ready;
                cpu_error = memory_error;
                cpu_read_data = memory_read_data;
            end

            STATE_WRITE: begin
                memory_valid = 1'b1;
                memory_write = 1'b1;
                memory_address = request_address;
                memory_write_data = request_write_data;
                cpu_ready = memory_ready;
                cpu_error = memory_error;
            end

            default: begin
                cpu_ready = 1'b0;
                memory_valid = 1'b0;
                memory_write = 1'b0;
            end
        endcase
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            state <= STATE_IDLE;
            request_address <= 64'd0;
            request_write_data <= 128'd0;
            request_write <= 1'b0;
            request_index <= '0;
            request_tag <= '0;
            line_valid <= '0;
            for (line_index = 0; line_index < LINES; line_index = line_index + 1) begin
                line_tag[line_index] <= '0;
                line_data[line_index] <= 128'd0;
            end
        end else begin
            case (state)
                STATE_IDLE: begin
                    if (cpu_valid && !cpu_cacheable) begin
                        request_address <= cpu_address;
                        request_write_data <= cpu_write_data;
                        request_write <= cpu_write;
                        state <= STATE_BYPASS;
                    end else if (cpu_valid && cpu_cacheable && cpu_write) begin
                        request_address <= cpu_address;
                        request_write_data <= cpu_write_data;
                        request_write <= 1'b1;
                        request_index <= lookup_index;
                        request_tag <= lookup_tag;
                        state <= STATE_WRITE;
                    end else if (cpu_valid && cpu_cacheable && !lookup_hit) begin
                        request_address <= cpu_address;
                        request_write_data <= 128'd0;
                        request_write <= 1'b0;
                        request_index <= lookup_index;
                        request_tag <= lookup_tag;
                        state <= STATE_MISS;
                    end
                end

                STATE_BYPASS: begin
                    if (memory_ready) state <= STATE_IDLE;
                end

                STATE_MISS: begin
                    if (memory_ready) begin
                        if (!memory_error) begin
                            line_valid[request_index] <= 1'b1;
                            line_tag[request_index] <= request_tag;
                            line_data[request_index] <= memory_read_data;
                        end
                        state <= STATE_IDLE;
                    end
                end

                STATE_WRITE: begin
                    if (memory_ready) begin
                        if (!memory_error) begin
                            // Write-through keeps backing RAM and the cache coherent.
                            line_valid[request_index] <= 1'b1;
                            line_tag[request_index] <= request_tag;
                            line_data[request_index] <= request_write_data;
                        end
                        state <= STATE_IDLE;
                    end
                end

                default: state <= STATE_IDLE;
            endcase
        end
    end
endmodule
