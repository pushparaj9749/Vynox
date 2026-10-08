// Static physical-region guard for the initial SoC privilege split.
// U mode is restricted to a configured RAM window; M mode retains platform access.
`timescale 1ns/1ps
module vynox128_mpu #(
    parameter logic [63:0] USER_RAM_BASE = 64'h0000_0000_0000_0000,
    parameter logic [63:0] USER_RAM_LIMIT = 64'h0000_0000_0000_0800
) (
    input  wire        request_valid,
    input  wire [63:0] address,
    input  wire [1:0]  privilege_mode,
    output logic       allowed
);
    localparam logic [1:0] PRIV_U = 2'b00;
    localparam logic [1:0] PRIV_M = 2'b11;

    wire [64:0] last_byte = {1'b0, address} + 65'd15;
    wire user_ram_access = (address >= USER_RAM_BASE) &&
        (last_byte < {1'b0, USER_RAM_LIMIT});

    initial begin
        if (USER_RAM_BASE >= USER_RAM_LIMIT) begin
            $fatal(1, "vynox128_mpu USER_RAM_BASE must be below USER_RAM_LIMIT");
        end
        if ((USER_RAM_BASE[3:0] != 4'd0) || (USER_RAM_LIMIT[3:0] != 4'd0)) begin
            $fatal(1, "vynox128_mpu user RAM region must be 16-byte aligned");
        end
    end

    always @* begin
        allowed = 1'b1;
        if (request_valid) begin
            case (privilege_mode)
                PRIV_M: allowed = 1'b1;
                PRIV_U: allowed = user_ram_access;
                default: allowed = 1'b0;
            endcase
        end
    end
endmodule
