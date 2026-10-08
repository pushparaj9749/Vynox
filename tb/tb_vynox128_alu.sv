`timescale 1ns/1ps

module tb_vynox128_alu;
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

    logic [127:0] lhs;
    logic [127:0] rhs;
    logic [3:0] operation;
    wire [127:0] result;
    wire zero;
    wire signed_less;
    wire unsigned_less;

    vynox128_alu dut (
        .lhs(lhs), .rhs(rhs), .operation(operation), .result(result),
        .zero(zero), .signed_less(signed_less), .unsigned_less(unsigned_less)
    );

    task automatic expect_result(input logic [127:0] expected, input string description);
        #1;
        if (result !== expected) begin
            $fatal(1, "%s: expected %032h, got %032h", description, expected, result);
        end
    endtask

    initial begin
        lhs = 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF;
        rhs = 128'd1;
        operation = ALU_ADD;
        expect_result(128'd0, "addition wraps at 128 bits");
        if (!zero) $fatal(1, "zero flag should be set after wrapped addition");

        lhs = 128'd5; rhs = 128'd3; operation = ALU_SUB;
        expect_result(128'd2, "subtraction");

        lhs = 128'h00FF; rhs = 128'h0F0F; operation = ALU_AND;
        expect_result(128'h000F, "AND");
        operation = ALU_OR;
        expect_result(128'h0FFF, "OR");
        operation = ALU_XOR;
        expect_result(128'h0FF0, "XOR");

        lhs = 128'd1; rhs = 128'd127; operation = ALU_SLL;
        expect_result(128'h8000_0000_0000_0000_0000_0000_0000_0000, "left shift by 127");
        operation = ALU_SRL;
        expect_result(128'd0, "logical right shift by 127");

        lhs = 128'h8000_0000_0000_0000_0000_0000_0000_0000;
        rhs = 128'd1; operation = ALU_SRA;
        expect_result(128'hC000_0000_0000_0000_0000_0000_0000_0000, "arithmetic right shift");

        lhs = 128'hFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF;
        rhs = 128'd1; operation = ALU_SLT;
        expect_result(128'd1, "signed less-than");
        if (!signed_less || unsigned_less) $fatal(1, "signed/unsigned compare flags are incorrect");
        operation = ALU_SLTU;
        expect_result(128'd0, "unsigned less-than");

        lhs = 128'h8000_0000_0000_0000_0000_0000_0000_0000;
        rhs = 128'd2; operation = ALU_MUL;
        expect_result(128'd0, "low-half multiply truncation");
        lhs = 128'd12; rhs = 128'd11;
        expect_result(128'd132, "multiply");

        $display("PASS: Vynox-128 ALU operations and edge cases");
        $finish;
    end
endmodule
