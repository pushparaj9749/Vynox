// 128-bit integer ALU for the Vynox-128 core.
`timescale 1ns/1ps
module vynox128_alu (
    input  logic [127:0] lhs,
    input  logic [127:0] rhs,
    input  logic [3:0]   operation,
    output logic [127:0] result,
    output wire         zero,
    output wire         signed_less,
    output wire         unsigned_less
);
    localparam logic [3:0] ALU_ADD  = 4'h0;
    localparam logic [3:0] ALU_SUB  = 4'h1;
    localparam logic [3:0] ALU_AND  = 4'h2;
    localparam logic [3:0] ALU_OR   = 4'h3;
    localparam logic [3:0] ALU_XOR  = 4'h4;
    localparam logic [3:0] ALU_SLL  = 4'h5;
    localparam logic [3:0] ALU_SRL  = 4'h6;
    localparam logic [3:0] ALU_SRA  = 4'h7;
    localparam logic [3:0] ALU_SLT  = 4'h8;
    localparam logic [3:0] ALU_SLTU = 4'h9;
    localparam logic [3:0] ALU_MUL  = 4'hA;

    always @* begin
        result = 128'd0;
        case (operation)
            ALU_ADD:  result = lhs + rhs;
            ALU_SUB:  result = lhs - rhs;
            ALU_AND:  result = lhs & rhs;
            ALU_OR:   result = lhs | rhs;
            ALU_XOR:  result = lhs ^ rhs;
            ALU_SLL:  result = lhs << rhs[6:0];
            ALU_SRL:  result = lhs >> rhs[6:0];
            ALU_SRA:  result = $unsigned($signed(lhs) >>> rhs[6:0]);
            ALU_SLT:  result = {127'd0, ($signed(lhs) < $signed(rhs))};
            ALU_SLTU: result = {127'd0, (lhs < rhs)};
            // MUL returns the low 128 bits of the product.
            ALU_MUL:  result = lhs * rhs;
            default:  result = 128'd0;
        endcase
    end

    assign zero = (result == 128'd0);
    assign signed_less = ($signed(lhs) < $signed(rhs));
    assign unsigned_less = (lhs < rhs);
endmodule
