`timescale 1ns/1ps

module tb_vynox128_uart_unit;
    localparam integer BIT_PERIOD_NS = 160;

    logic clk;
    logic reset;
    logic enable;
    logic rx;
    logic [31:0] divisor;
    logic tx_valid;
    logic [7:0] tx_data;
    logic rx_consume;
    logic rx_clear_error;
    logic rx_irq_enable;
    wire tx;
    wire tx_ready;
    wire tx_busy;
    wire [7:0] last_tx;
    wire [7:0] rx_data;
    wire rx_valid;
    wire rx_error;
    wire rx_irq;
    integer bit_index;

    vynox128_uart dut (
        .clk(clk), .reset(reset), .enable(enable), .rx(rx), .tx(tx),
        .divisor(divisor), .tx_valid(tx_valid), .tx_data(tx_data),
        .tx_ready(tx_ready), .tx_busy(tx_busy), .last_tx(last_tx),
        .rx_data(rx_data), .rx_valid(rx_valid), .rx_consume(rx_consume),
        .rx_error(rx_error), .rx_clear_error(rx_clear_error),
        .rx_irq_enable(rx_irq_enable), .rx_irq(rx_irq)
    );

    always #5 clk = ~clk;

    task automatic send_uart_byte(input logic [7:0] value);
        begin
            rx = 1'b0;
            #(BIT_PERIOD_NS);
            for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
                rx = value[bit_index];
                #(BIT_PERIOD_NS);
            end
            rx = 1'b1;
            #(BIT_PERIOD_NS);
        end
    endtask

    task automatic send_bad_stop_byte(input logic [7:0] value);
        begin
            rx = 1'b0;
            #(BIT_PERIOD_NS);
            for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
                rx = value[bit_index];
                #(BIT_PERIOD_NS);
            end
            rx = 1'b0; // invalid stop bit
            #(BIT_PERIOD_NS);
            rx = 1'b1;
            #(BIT_PERIOD_NS);
        end
    endtask

    task automatic consume_byte;
        begin
            @(negedge clk);
            rx_consume = 1'b1;
            @(negedge clk);
            rx_consume = 1'b0;
        end
    endtask

    task automatic clear_error;
        begin
            @(negedge clk);
            rx_clear_error = 1'b1;
            @(negedge clk);
            rx_clear_error = 1'b0;
        end
    endtask

    initial begin
        clk = 1'b0;
        reset = 1'b1;
        enable = 1'b1;
        rx = 1'b1;
        divisor = 32'd16;
        tx_valid = 1'b0;
        tx_data = 8'd0;
        rx_consume = 1'b0;
        rx_clear_error = 1'b0;
        rx_irq_enable = 1'b1;
        #17 reset = 1'b0;

        send_uart_byte(8'h5A);
        if (rx_valid !== 1'b1 || rx_data !== 8'h5A || rx_irq !== 1'b1) begin
            $fatal(1, "UART RX failed to receive the first byte or assert IRQ");
        end
        consume_byte();
        if (rx_valid !== 1'b0 || rx_irq !== 1'b0) begin
            $fatal(1, "UART RX consume did not clear valid/IRQ");
        end

        send_uart_byte(8'hA5);
        send_uart_byte(8'h3C); // overrun: leave A5 in the holding register
        if (rx_valid !== 1'b1 || rx_data !== 8'hA5 || rx_error !== 1'b1) begin
            $fatal(1, "UART RX overrun did not preserve old byte and set error");
        end
        clear_error();
        if (rx_error !== 1'b0 || rx_valid !== 1'b1) begin
            $fatal(1, "UART RX error clear disturbed the pending byte");
        end
        consume_byte();

        send_bad_stop_byte(8'hC3);
        if (rx_valid !== 1'b0 || rx_error !== 1'b1) begin
            $fatal(1, "UART RX framing error was not detected");
        end
        clear_error();
        if (rx_error !== 1'b0) $fatal(1, "UART RX framing error did not clear");

        $display("PASS: UART RX data, IRQ, overrun retention, framing error, and error clear");
        $finish;
    end

    initial begin
        #20000;
        $fatal(1, "Timed out in UART module unit test");
    end
endmodule
