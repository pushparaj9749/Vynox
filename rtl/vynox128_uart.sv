// Full-duplex 8N1 UART with a single-byte RX holding register.
`timescale 1ns/1ps
module vynox128_uart (
    input  wire        clk,
    input  wire        reset,
    input  wire        enable,
    input  wire        rx,
    output logic       tx,
    input  wire [31:0] divisor,

    input  wire        tx_valid,
    input  wire [7:0]  tx_data,
    output wire        tx_ready,
    output logic       tx_busy,
    output logic [7:0] last_tx,

    output logic [7:0] rx_data,
    output logic       rx_valid,
    input  wire        rx_consume,
    output logic       rx_error,
    input  wire        rx_clear_error,
    input  wire        rx_irq_enable,
    output wire        rx_irq
);
    localparam logic [1:0] RX_IDLE  = 2'd0;
    localparam logic [1:0] RX_START = 2'd1;
    localparam logic [1:0] RX_DATA  = 2'd2;
    localparam logic [1:0] RX_STOP  = 2'd3;

    logic [9:0] tx_frame;
    logic [3:0] tx_bit_index;
    logic [31:0] tx_div_count;
    logic [31:0] tx_active_divisor;

    logic rx_meta;
    logic rx_sync;
    logic [1:0] rx_state;
    logic [31:0] rx_countdown;
    logic [31:0] rx_active_divisor;
    logic [2:0] rx_bit_index;
    logic [7:0] rx_shift;

    wire [31:0] safe_divisor = (divisor == 32'd0) ? 32'd1 : divisor;
    wire rx_stop_sample = (rx_state == RX_STOP) && (rx_countdown == 32'd0);

    assign tx_ready = enable && !tx_busy;
    assign rx_irq = enable && rx_irq_enable && rx_valid;

    always_ff @(posedge clk) begin
        if (reset) begin
            tx <= 1'b1;
            tx_busy <= 1'b0;
            tx_frame <= 10'h3FF;
            tx_bit_index <= 4'd0;
            tx_div_count <= 32'd0;
            tx_active_divisor <= 32'd1;
            last_tx <= 8'd0;

            rx_meta <= 1'b1;
            rx_sync <= 1'b1;
            rx_state <= RX_IDLE;
            rx_countdown <= 32'd0;
            rx_active_divisor <= 32'd1;
            rx_bit_index <= 3'd0;
            rx_shift <= 8'd0;
            rx_data <= 8'd0;
            rx_valid <= 1'b0;
            rx_error <= 1'b0;
        end else begin
            // Two-flop synchronizer for the asynchronous serial input.
            rx_meta <= rx;
            rx_sync <= rx_meta;

            // TX holds each start/data/stop bit for the programmed divisor.
            if (tx_busy) begin
                if (tx_div_count >= (tx_active_divisor - 32'd1)) begin
                    tx_div_count <= 32'd0;
                    if (tx_bit_index == 4'd9) begin
                        tx_busy <= 1'b0;
                        tx <= 1'b1;
                    end else begin
                        tx_bit_index <= tx_bit_index + 4'd1;
                        tx <= tx_frame[tx_bit_index + 4'd1];
                    end
                end else begin
                    tx_div_count <= tx_div_count + 32'd1;
                end
            end

            // Consume/clear requests share the RX state machine so a completed
            // byte arriving on the same edge is retained instead of lost.
            if (rx_consume) rx_valid <= 1'b0;
            if (rx_clear_error) rx_error <= 1'b0;

            if (!enable) begin
                rx_state <= RX_IDLE;
                rx_countdown <= 32'd0;
            end else begin
                case (rx_state)
                    RX_IDLE: begin
                        if (!rx_sync) begin
                            rx_state <= RX_START;
                            rx_active_divisor <= safe_divisor;
                            rx_countdown <= (safe_divisor > 32'd2) ? (safe_divisor >> 1) : 32'd1;
                        end
                    end

                    RX_START: begin
                        if (rx_countdown != 32'd0) begin
                            rx_countdown <= rx_countdown - 32'd1;
                        end else if (!rx_sync) begin
                            rx_state <= RX_DATA;
                            rx_bit_index <= 3'd0;
                            rx_countdown <= rx_active_divisor - 32'd1;
                        end else begin
                            // A short low pulse was not a valid start bit.
                            rx_state <= RX_IDLE;
                        end
                    end

                    RX_DATA: begin
                        if (rx_countdown != 32'd0) begin
                            rx_countdown <= rx_countdown - 32'd1;
                        end else begin
                            rx_shift[rx_bit_index] <= rx_sync;
                            rx_countdown <= rx_active_divisor - 32'd1;
                            if (rx_bit_index == 3'd7) begin
                                rx_state <= RX_STOP;
                            end else begin
                                rx_bit_index <= rx_bit_index + 3'd1;
                            end
                        end
                    end

                    RX_STOP: begin
                        if (rx_countdown != 32'd0) begin
                            rx_countdown <= rx_countdown - 32'd1;
                        end else begin
                            if (rx_sync) begin
                                if (rx_valid && !rx_consume) begin
                                    rx_error <= 1'b1; // overrun: software did not drain prior byte
                                end else begin
                                    rx_data <= rx_shift;
                                    rx_valid <= 1'b1;
                                end
                            end else begin
                                rx_error <= 1'b1; // framing error: stop bit was low
                            end
                            rx_state <= RX_IDLE;
                        end
                    end

                    default: begin
                        rx_state <= RX_IDLE;
                        rx_error <= 1'b1;
                    end
                endcase
            end

            // Start a new byte only after the previous stop bit has completed.
            if (tx_valid && tx_ready) begin
                tx_frame <= {1'b1, tx_data, 1'b0};
                tx_bit_index <= 4'd0;
                tx_div_count <= 32'd0;
                tx_active_divisor <= safe_divisor;
                tx_busy <= 1'b1;
                tx <= 1'b0;
                last_tx <= tx_data;
            end
        end
    end
endmodule
