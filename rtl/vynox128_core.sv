// Five-stage, in-order Vynox-128 core with bypassing, load-use interlock,
// control-flow flushing, precise synchronous faults, and drain-before-IRQ entry.
`timescale 1ns/1ps
module vynox128_core #(
    parameter logic [63:0] IRQ_VECTOR = 64'h0000_0000_0000_0100
) (
    input  wire          clk,
    input  wire          reset,
    input  wire          irq,

    input  wire [63:0]   instruction,
    input  wire          instruction_ready,
    input  wire          instruction_error,
    input  wire [63:0]   instruction_fault_cause,
    input  wire [63:0]   instruction_fault_address,
    output wire [63:0]   instruction_address,
    output logic         instruction_flush,
    output logic         mmu_flush,
    output wire [63:0]   satp_value,

    output wire          data_valid,
    output wire          data_write_enable,
    output wire [63:0]   data_address,
    output wire [127:0]  data_write_data,
    input  wire          data_access_allowed,
    input  wire          data_translation_ready,
    input  wire [63:0]   data_translation_address,
    input  wire          data_translation_fault,
    input  wire [63:0]   data_translation_fault_cause,
    input  wire [63:0]   data_translation_fault_address,
    output wire [1:0]    privilege_mode_out,
    output wire          ex_data_request,
    output wire          ex_data_write,
    output wire [63:0]  ex_data_address,
    input  wire          data_ready,
    input  wire          data_error,
    input  wire [127:0]  data_read_data,

    input  wire [127:0]  gpio_in,
    output logic [127:0] gpio_out,
    output logic         halted,
    output logic         fault,
    output logic [7:0]   fault_code
);
    localparam logic [7:0] OP_NOP   = 8'h00;
    localparam logic [7:0] OP_ADD   = 8'h01;
    localparam logic [7:0] OP_SUB   = 8'h02;
    localparam logic [7:0] OP_AND   = 8'h03;
    localparam logic [7:0] OP_OR    = 8'h04;
    localparam logic [7:0] OP_XOR   = 8'h05;
    localparam logic [7:0] OP_SLL   = 8'h06;
    localparam logic [7:0] OP_SRL   = 8'h07;
    localparam logic [7:0] OP_SRA   = 8'h08;
    localparam logic [7:0] OP_SLT   = 8'h09;
    localparam logic [7:0] OP_SLTU  = 8'h0A;
    localparam logic [7:0] OP_ADDI  = 8'h0B;
    localparam logic [7:0] OP_ANDI  = 8'h0C;
    localparam logic [7:0] OP_ORI   = 8'h0D;
    localparam logic [7:0] OP_XORI  = 8'h0E;
    localparam logic [7:0] OP_MUL   = 8'h0F;
    localparam logic [7:0] OP_LOAD  = 8'h10;
    localparam logic [7:0] OP_STORE = 8'h11;
    localparam logic [7:0] OP_BEQ   = 8'h20;
    localparam logic [7:0] OP_BNE   = 8'h21;
    localparam logic [7:0] OP_BLT   = 8'h22;
    localparam logic [7:0] OP_BGE   = 8'h23;
    localparam logic [7:0] OP_BLTU  = 8'h24;
    localparam logic [7:0] OP_BGEU  = 8'h25;
    localparam logic [7:0] OP_JAL   = 8'h30;
    localparam logic [7:0] OP_IN    = 8'h40;
    localparam logic [7:0] OP_OUT   = 8'h41;
    localparam logic [7:0] OP_EI    = 8'h42;
    localparam logic [7:0] OP_DI    = 8'h43;
    localparam logic [7:0] OP_IRET  = 8'h44; // compatibility alias for MRET
    localparam logic [7:0] OP_CSRR  = 8'h45;
    localparam logic [7:0] OP_CSRW  = 8'h46;
    localparam logic [7:0] OP_MRET  = 8'h47;
    localparam logic [7:0] OP_ECALL = 8'h48;
    localparam logic [7:0] OP_EBREAK = 8'h49;
    localparam logic [7:0] OP_FENCE  = 8'h4A;
    localparam logic [7:0] OP_FENCEI = 8'h4B;
    localparam logic [7:0] OP_SFENCE = 8'h4C;
    localparam logic [7:0] OP_HALT  = 8'hFF;

    localparam logic [11:0] CSR_MSTATUS  = 12'h300;
    localparam logic [11:0] CSR_MIE      = 12'h304;
    localparam logic [11:0] CSR_MTVEC    = 12'h305;
    localparam logic [11:0] CSR_MSCRATCH = 12'h340;
    localparam logic [11:0] CSR_MEPC     = 12'h341;
    localparam logic [11:0] CSR_MCAUSE   = 12'h342;
    localparam logic [11:0] CSR_MTVAL    = 12'h343;
    localparam logic [11:0] CSR_MIP      = 12'h344;
    localparam logic [11:0] CSR_MCYCLE   = 12'hC00;
    localparam logic [11:0] CSR_MINSTRET = 12'hC02;
    localparam logic [11:0] CSR_SATP     = 12'h180;

    localparam logic [1:0] PRIV_U = 2'b00;
    localparam logic [1:0] PRIV_M = 2'b11;

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

    localparam logic [7:0] FAULT_NONE        = 8'h00;
    localparam logic [7:0] FAULT_ILLEGAL     = 8'h01;
    localparam logic [7:0] FAULT_ALIGNMENT   = 8'h02;
    localparam logic [7:0] FAULT_DATA_BUS    = 8'h03;
    localparam logic [7:0] FAULT_FETCH_RANGE = 8'h04;
    localparam logic [7:0] FAULT_ECALL       = 8'h05;
    localparam logic [7:0] FAULT_BREAKPOINT  = 8'h06;
    localparam logic [7:0] FAULT_PROTECTION  = 8'h07;
    localparam logic [7:0] FAULT_PAGE        = 8'h08;

    logic [127:0] registers [0:31];

    // IF/ID
    logic        ifid_valid;
    logic [63:0] ifid_pc;
    logic [63:0] ifid_instruction;
    logic [7:0]  ifid_fault_code;
    logic [63:0] ifid_fault_cause;
    logic [63:0] ifid_fault_address;

    // ID/EX: instruction and PC are decoded combinationally; operands are read
    // from the register file and forwarded immediately before the EX stage.
    logic        idex_valid;
    logic [63:0] idex_pc;
    logic [63:0] idex_instruction;
    logic [7:0]  idex_fault_code;
    logic [63:0] idex_fault_cause;
    logic [63:0] idex_fault_address;

    // EX/MEM
    logic         exmem_valid;
    logic [63:0]  exmem_pc;
    logic [63:0]  exmem_instruction;
    logic [4:0]   exmem_rd;
    logic         exmem_reg_write;
    logic [127:0] exmem_result;
    logic [63:0]  exmem_address;
    logic [63:0]  exmem_virtual_address;
    logic [127:0] exmem_store_data;
    logic         exmem_mem_read;
    logic         exmem_mem_write;
    logic         exmem_gpio_write;
    logic [127:0] exmem_gpio_data;
    logic         exmem_ei;
    logic         exmem_di;
    logic         exmem_iret;
    logic         exmem_halt;
    logic         exmem_fence;
    logic         exmem_exception;
    logic [7:0]   exmem_fault_code;
    logic [63:0]  exmem_mcause;
    logic [63:0]  exmem_mtval;
    logic         exmem_csr_write;
    logic [11:0]  exmem_csr_address;
    logic [63:0]  exmem_csr_wdata;

    // MEM/WB
    logic         memwb_valid;
    logic [63:0]  memwb_pc;
    logic [63:0]  memwb_instruction;
    logic [4:0]   memwb_rd;
    logic         memwb_reg_write;
    logic [127:0] memwb_result;
    logic         memwb_gpio_write;
    logic [127:0] memwb_gpio_data;
    logic         memwb_ei;
    logic         memwb_di;
    logic         memwb_iret;
    logic         memwb_halt;
    logic         memwb_fence;
    logic         memwb_exception;
    logic [7:0]   memwb_fault_code;
    logic [63:0]  memwb_mcause;
    logic [63:0]  memwb_mtval;
    logic         memwb_csr_write;
    logic [11:0]  memwb_csr_address;
    logic [63:0]  memwb_csr_wdata;

    logic [63:0] fetch_pc;
    logic [63:0] mstatus;
    logic [63:0] mie;
    logic [63:0] mtvec;
    logic [63:0] mepc;
    logic [63:0] mcause;
    logic [63:0] mtval;
    logic [63:0] mscratch;
    logic [63:0] cycle_counter;
    logic [63:0] instret_counter;
    logic [63:0] satp;
    logic [1:0]  privilege_mode;
    logic        in_interrupt;
    logic        irq_drain_pending;
    logic        front_stopped;
    logic        terminal_pending;

    wire [7:0] idex_opcode = idex_instruction[63:56];
    wire [4:0] idex_rd = idex_instruction[55:51];
    wire [4:0] idex_rs1 = idex_instruction[50:46];
    wire [4:0] idex_rs2 = idex_instruction[45:41];
    wire [31:0] idex_immediate = idex_instruction[40:9];
    wire [63:0] idex_immediate_64 = {{32{idex_immediate[31]}}, idex_immediate};
    wire [127:0] idex_immediate_128 = {{96{idex_immediate[31]}}, idex_immediate};
    wire [11:0] idex_csr_address = idex_immediate[11:0];
    wire [4:0] idex_src_a_index = (idex_opcode == OP_OUT) ? idex_rd : idex_rs1;
    logic [63:0] csr_read_value;

    logic [127:0] ex_operand_a;
    logic [127:0] ex_operand_b_register;
    logic [127:0] ex_alu_rhs;
    logic [3:0]   ex_alu_operation;
    wire [127:0]  ex_alu_result;
    wire          ex_alu_zero;
    wire          ex_alu_signed_less;
    wire          ex_alu_unsigned_less;
    wire [63:0]   ex_effective_address = ex_operand_a[63:0] + idex_immediate_64;
    wire [63:0]   ex_branch_target = idex_pc + (idex_immediate_64 << 3);

    logic         ex_is_legal;
    logic         ex_exception;
    logic [7:0]   ex_fault_code;
    logic [63:0]  ex_mcause;
    logic [63:0]  ex_mtval;
    logic         ex_csr_write;
    logic [11:0]  ex_csr_address;
    logic [63:0]  ex_csr_wdata;
    logic         ex_reg_write;
    logic [4:0]   ex_destination;
    logic [127:0] ex_result;
    logic         ex_mem_read;
    logic         ex_mem_write;
    logic         ex_gpio_write;
    logic [127:0] ex_gpio_data;
    logic         ex_ei;
    logic         ex_di;
    logic         ex_iret;
    logic         ex_halt;
    logic         ex_fence;
    logic         ex_fence_i;
    logic         ex_mmu_flush;
    logic         ex_branch_taken;
    logic [63:0]  ex_redirect_target;
    logic         ex_redirect;

    wire exmem_memory_wait = exmem_valid && (exmem_mem_read || exmem_mem_write) && !data_ready;
    wire exmem_data_fault = exmem_valid && (exmem_mem_read || exmem_mem_write) && data_ready && data_error;
    wire pipeline_empty = !ifid_valid && !idex_valid && !exmem_valid && !memwb_valid;

    logic [63:0] mstatus_after_wb;
    logic [63:0] mie_after_wb;
    wire irq_can_enter = mie_after_wb[11] &&
        ((privilege_mode == PRIV_U) || mstatus_after_wb[3]);
    wire irq_start = irq && irq_can_enter && !terminal_pending;
    wire irq_take = !halted && !exmem_memory_wait && !exmem_data_fault &&
        !terminal_pending && irq_can_enter && pipeline_empty &&
        (irq_drain_pending || irq_start);
    wire csr_read_stall = idex_valid && (idex_opcode == OP_CSRR) &&
        ((exmem_valid && exmem_csr_write && (exmem_csr_address == idex_csr_address)) ||
         (memwb_valid && memwb_csr_write && (memwb_csr_address == idex_csr_address)));
    wire data_translation_stall = idex_valid && (idex_fault_code == FAULT_NONE) &&
        ((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) &&
        (ex_effective_address[3:0] == 4'd0) && !data_translation_ready;

    integer register_index;

    assign instruction_address = fetch_pc;
    assign satp_value = satp;
    assign privilege_mode_out = privilege_mode;
    assign ex_data_request = idex_valid && (idex_fault_code == FAULT_NONE) &&
        ((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) &&
        (ex_effective_address[3:0] == 4'd0);
    assign ex_data_write = ex_data_request && (idex_opcode == OP_STORE);
    assign ex_data_address = ex_effective_address;
    assign data_valid = exmem_valid && (exmem_mem_read || exmem_mem_write);
    assign data_write_enable = exmem_valid && exmem_mem_write;
    assign data_address = exmem_address;
    assign data_write_data = exmem_store_data;

    vynox128_alu alu (
        .lhs(ex_operand_a),
        .rhs(ex_alu_rhs),
        .operation(ex_alu_operation),
        .result(ex_alu_result),
        .zero(ex_alu_zero),
        .signed_less(ex_alu_signed_less),
        .unsigned_less(ex_alu_unsigned_less)
    );

    function automatic logic opcode_is_legal(input logic [7:0] opcode);
        begin
            case (opcode)
                OP_NOP, OP_ADD, OP_SUB, OP_AND, OP_OR, OP_XOR,
                OP_SLL, OP_SRL, OP_SRA, OP_SLT, OP_SLTU, OP_ADDI,
                OP_ANDI, OP_ORI, OP_XORI, OP_MUL, OP_LOAD, OP_STORE,
                OP_BEQ, OP_BNE, OP_BLT, OP_BGE, OP_BLTU, OP_BGEU,
                OP_JAL, OP_IN, OP_OUT, OP_EI, OP_DI, OP_IRET, OP_CSRR,
                OP_CSRW, OP_MRET, OP_ECALL, OP_EBREAK, OP_FENCE, OP_FENCEI,
                OP_SFENCE, OP_HALT:
                    opcode_is_legal = 1'b1;
                default: opcode_is_legal = 1'b0;
            endcase
        end
    endfunction

    function automatic logic csr_is_known(input logic [11:0] address);
        begin
            case (address)
                CSR_MSTATUS, CSR_MIE, CSR_MTVEC, CSR_MSCRATCH, CSR_MEPC,
                CSR_MCAUSE, CSR_MTVAL, CSR_MIP, CSR_MCYCLE, CSR_MINSTRET, CSR_SATP:
                    csr_is_known = 1'b1;
                default: csr_is_known = 1'b0;
            endcase
        end
    endfunction

    function automatic logic csr_is_writable(input logic [11:0] address);
        begin
            case (address)
                CSR_MSTATUS, CSR_MIE, CSR_MTVEC, CSR_MSCRATCH, CSR_MEPC,
                CSR_MCAUSE, CSR_MTVAL, CSR_SATP:
                    csr_is_writable = 1'b1;
                default: csr_is_writable = 1'b0;
            endcase
        end
    endfunction

    function automatic logic [63:0] make_trap_target(
        input logic [63:0] vector_value,
        input logic is_interrupt,
        input logic [63:0] cause_value
    );
        logic [63:0] base_address;
        begin
            base_address = {vector_value[63:3], 3'b000};
            if (is_interrupt && (vector_value[1:0] == 2'b01)) begin
                make_trap_target = base_address + (cause_value << 3);
            end else begin
                make_trap_target = base_address;
            end
        end
    endfunction

    function automatic logic uses_source_a(input logic [7:0] opcode);
        begin
            case (opcode)
                OP_ADD, OP_SUB, OP_AND, OP_OR, OP_XOR,
                OP_SLL, OP_SRL, OP_SRA, OP_SLT, OP_SLTU, OP_MUL,
                OP_ADDI, OP_ANDI, OP_ORI, OP_XORI, OP_LOAD, OP_STORE,
                OP_BEQ, OP_BNE, OP_BLT, OP_BGE, OP_BLTU, OP_BGEU, OP_OUT, OP_CSRW:
                    uses_source_a = 1'b1;
                default: uses_source_a = 1'b0;
            endcase
        end
    endfunction

    function automatic logic uses_source_b(input logic [7:0] opcode);
        begin
            case (opcode)
                OP_ADD, OP_SUB, OP_AND, OP_OR, OP_XOR,
                OP_SLL, OP_SRL, OP_SRA, OP_SLT, OP_SLTU, OP_MUL,
                OP_STORE, OP_BEQ, OP_BNE, OP_BLT, OP_BGE, OP_BLTU, OP_BGEU:
                    uses_source_b = 1'b1;
                default: uses_source_b = 1'b0;
            endcase
        end
    endfunction

    // EX-stage forwarding. A load in EX/MEM is interlocked for one cycle; its
    // value is then forwarded from MEM/WB, avoiding a combinational RAM->ALU path.
    always_comb begin
        ex_operand_a = (idex_src_a_index == 5'd0) ? 128'd0 : registers[idex_src_a_index];
        ex_operand_b_register = (idex_rs2 == 5'd0) ? 128'd0 : registers[idex_rs2];

        if ((idex_src_a_index != 5'd0) && exmem_valid && exmem_reg_write &&
            !exmem_mem_read && (exmem_rd == idex_src_a_index)) begin
            ex_operand_a = exmem_result;
        end else if ((idex_src_a_index != 5'd0) && memwb_valid && memwb_reg_write &&
                     (memwb_rd == idex_src_a_index)) begin
            ex_operand_a = memwb_result;
        end

        if ((idex_rs2 != 5'd0) && exmem_valid && exmem_reg_write &&
            !exmem_mem_read && (exmem_rd == idex_rs2)) begin
            ex_operand_b_register = exmem_result;
        end else if ((idex_rs2 != 5'd0) && memwb_valid && memwb_reg_write &&
                     (memwb_rd == idex_rs2)) begin
            ex_operand_b_register = memwb_result;
        end

        ex_alu_operation = ALU_ADD;
        ex_alu_rhs = ex_operand_b_register;
        case (idex_opcode)
            OP_SUB, OP_BEQ, OP_BNE: ex_alu_operation = ALU_SUB;
            OP_AND:                 ex_alu_operation = ALU_AND;
            OP_OR:                  ex_alu_operation = ALU_OR;
            OP_XOR:                 ex_alu_operation = ALU_XOR;
            OP_SLL:                 ex_alu_operation = ALU_SLL;
            OP_SRL:                 ex_alu_operation = ALU_SRL;
            OP_SRA:                 ex_alu_operation = ALU_SRA;
            OP_SLT:                 ex_alu_operation = ALU_SLT;
            OP_SLTU:                ex_alu_operation = ALU_SLTU;
            OP_MUL:                 ex_alu_operation = ALU_MUL;
            OP_ADDI, OP_LOAD, OP_STORE: begin
                ex_alu_operation = ALU_ADD;
                ex_alu_rhs = idex_immediate_128;
            end
            OP_ANDI: begin
                ex_alu_operation = ALU_AND;
                ex_alu_rhs = idex_immediate_128;
            end
            OP_ORI: begin
                ex_alu_operation = ALU_OR;
                ex_alu_rhs = idex_immediate_128;
            end
            OP_XORI: begin
                ex_alu_operation = ALU_XOR;
                ex_alu_rhs = idex_immediate_128;
            end
            default: begin
                ex_alu_operation = ALU_ADD;
                ex_alu_rhs = ex_operand_b_register;
            end
        endcase
    end

    always @* begin
        csr_read_value = 64'd0;
        case (idex_csr_address)
            CSR_MSTATUS:  csr_read_value = mstatus;
            CSR_MIE:      csr_read_value = mie;
            CSR_MTVEC:    csr_read_value = mtvec;
            CSR_MSCRATCH: csr_read_value = mscratch;
            CSR_MEPC:     csr_read_value = mepc;
            CSR_MCAUSE:   csr_read_value = mcause;
            CSR_MTVAL:    csr_read_value = mtval;
            CSR_MIP:      csr_read_value = irq ? 64'h0000_0000_0000_0800 : 64'd0;
            CSR_MCYCLE:   csr_read_value = cycle_counter;
            CSR_MINSTRET: csr_read_value = instret_counter;
            CSR_SATP:     csr_read_value = satp;
            default:      csr_read_value = 64'd0;
        endcase
    end

    always @* begin
        ex_is_legal = opcode_is_legal(idex_opcode);
        ex_exception = 1'b0;
        ex_fault_code = FAULT_NONE;
        ex_mcause = 64'd0;
        ex_mtval = 64'd0;
        ex_csr_write = 1'b0;
        ex_csr_address = idex_csr_address;
        ex_csr_wdata = ex_operand_a[63:0];
        ex_reg_write = 1'b0;
        ex_destination = idex_rd;
        ex_result = ex_alu_result;
        ex_mem_read = 1'b0;
        ex_mem_write = 1'b0;
        ex_gpio_write = 1'b0;
        ex_gpio_data = ex_operand_a;
        ex_ei = 1'b0;
        ex_di = 1'b0;
        ex_iret = 1'b0;
        ex_halt = 1'b0;
        ex_fence = 1'b0;
        ex_fence_i = 1'b0;
        ex_mmu_flush = 1'b0;
        ex_branch_taken = 1'b0;
        ex_redirect_target = ex_branch_target;
        ex_redirect = 1'b0;

        if (idex_valid) begin
            if (idex_fault_code != FAULT_NONE) begin
                ex_exception = 1'b1;
                ex_fault_code = idex_fault_code;
                ex_mcause = idex_fault_cause;
                ex_mtval = idex_fault_address;
            end else if (!ex_is_legal) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_ILLEGAL;
                ex_mcause = 64'd2;
                ex_mtval = idex_instruction;
            end else if (((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) &&
                         (ex_effective_address[3:0] != 4'd0)) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_ALIGNMENT;
                ex_mcause = (idex_opcode == OP_LOAD) ? 64'd4 : 64'd6;
                ex_mtval = ex_effective_address;
            end else if (((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) &&
                         data_translation_fault) begin
                ex_exception = 1'b1;
                ex_fault_code = ((data_translation_fault_cause == 64'd12) ||
                                 (data_translation_fault_cause == 64'd13) ||
                                 (data_translation_fault_cause == 64'd15)) ?
                                FAULT_PAGE : FAULT_DATA_BUS;
                ex_mcause = data_translation_fault_cause;
                ex_mtval = data_translation_fault_address;
            end else if (((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) &&
                         !data_access_allowed) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_PROTECTION;
                ex_mcause = (idex_opcode == OP_LOAD) ? 64'd5 : 64'd7;
                ex_mtval = ex_effective_address;
            end else if (((idex_opcode == OP_CSRR) &&
                          ((privilege_mode != PRIV_M) || !csr_is_known(idex_csr_address))) ||
                         ((idex_opcode == OP_CSRW) &&
                          ((privilege_mode != PRIV_M) || !csr_is_writable(idex_csr_address))) ||
                         (((idex_opcode == OP_EI) || (idex_opcode == OP_DI) ||
                           (idex_opcode == OP_IRET) || (idex_opcode == OP_MRET) ||
                           (idex_opcode == OP_SFENCE) || (idex_opcode == OP_HALT)) &&
                          (privilege_mode != PRIV_M))) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_ILLEGAL;
                ex_mcause = 64'd2;
                ex_mtval = idex_instruction;
            end else if (idex_opcode == OP_ECALL) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_ECALL;
                ex_mcause = (privilege_mode == PRIV_U) ? 64'd8 : 64'd11;
                ex_mtval = 64'd0;
            end else if (idex_opcode == OP_EBREAK) begin
                ex_exception = 1'b1;
                ex_fault_code = FAULT_BREAKPOINT;
                ex_mcause = 64'd3;
                ex_mtval = idex_pc;
            end else begin
                case (idex_opcode)
                    OP_ADD, OP_SUB, OP_AND, OP_OR, OP_XOR,
                    OP_SLL, OP_SRL, OP_SRA, OP_SLT, OP_SLTU, OP_MUL,
                    OP_ADDI, OP_ANDI, OP_ORI, OP_XORI: begin
                        ex_reg_write = (idex_rd != 5'd0);
                    end
                    OP_LOAD: begin
                        ex_reg_write = (idex_rd != 5'd0);
                        ex_mem_read = 1'b1;
                        ex_result = {64'd0, ex_effective_address};
                    end
                    OP_STORE: begin
                        ex_mem_write = 1'b1;
                        ex_result = {64'd0, ex_effective_address};
                    end
                    OP_BEQ: ex_branch_taken = ex_alu_zero;
                    OP_BNE: ex_branch_taken = !ex_alu_zero;
                    OP_BLT: ex_branch_taken = ex_alu_signed_less;
                    OP_BGE: ex_branch_taken = !ex_alu_signed_less;
                    OP_BLTU: ex_branch_taken = ex_alu_unsigned_less;
                    OP_BGEU: ex_branch_taken = !ex_alu_unsigned_less;
                    OP_JAL: begin
                        ex_reg_write = (idex_rd != 5'd0);
                        ex_result = {64'd0, idex_pc + 64'd8};
                        ex_redirect = 1'b1;
                    end
                    OP_IN: begin
                        ex_reg_write = (idex_rd != 5'd0);
                        ex_result = gpio_in;
                    end
                    OP_OUT: begin
                        ex_gpio_write = 1'b1;
                        ex_gpio_data = ex_operand_a;
                    end
                    OP_EI: ex_ei = 1'b1;
                    OP_DI: ex_di = 1'b1;
                    OP_IRET, OP_MRET: ex_iret = 1'b1;
                    OP_CSRR: begin
                        ex_reg_write = (idex_rd != 5'd0);
                        ex_result = {64'd0, csr_read_value};
                    end
                    OP_CSRW: begin
                        ex_csr_write = 1'b1;
                        if (idex_csr_address == CSR_SATP) begin
                            ex_fence = 1'b1;
                            ex_mmu_flush = 1'b1;
                        end
                    end
                    OP_FENCE: ex_fence = 1'b1;
                    OP_FENCEI: begin
                        ex_fence = 1'b1;
                        ex_fence_i = 1'b1;
                    end
                    OP_SFENCE: begin
                        ex_fence = 1'b1;
                        ex_mmu_flush = 1'b1;
                    end
                    OP_HALT: ex_halt = 1'b1;
                    default: begin end
                endcase

                if ((idex_opcode == OP_BEQ) || (idex_opcode == OP_BNE) ||
                    (idex_opcode == OP_BLT) || (idex_opcode == OP_BGE) ||
                    (idex_opcode == OP_BLTU) || (idex_opcode == OP_BGEU)) begin
                    ex_redirect = ex_branch_taken;
                end
            end
        end
    end

    wire load_use_stall = idex_valid && (idex_fault_code == FAULT_NONE) &&
        opcode_is_legal(idex_opcode) && exmem_valid && exmem_mem_read &&
        exmem_reg_write && (exmem_rd != 5'd0) &&
        ((uses_source_a(idex_opcode) && (idex_src_a_index == exmem_rd)) ||
         (uses_source_b(idex_opcode) && (idex_rs2 == exmem_rd)));

    always @* begin
        mstatus_after_wb = mstatus;
        mie_after_wb = mie;
        if (memwb_valid && memwb_csr_write && (memwb_csr_address == CSR_MSTATUS)) begin
            mstatus_after_wb[3] = memwb_csr_wdata[3];
        end
        if (memwb_valid && memwb_csr_write && (memwb_csr_address == CSR_MIE)) begin
            mie_after_wb[11] = memwb_csr_wdata[11];
        end
        if (memwb_valid && memwb_ei) mstatus_after_wb[3] = 1'b1;
        if (memwb_valid && memwb_di) mstatus_after_wb[3] = 1'b0;
    end

    // All architectural side effects retire in order in MEM/WB. Traps save a
    // precise PC/cause/value, switch to machine mode, and vector through MTVEC.
    always_ff @(posedge clk) begin
        if (reset) begin
            fetch_pc <= 64'd0;
            mstatus <= 64'h0000_0000_0000_1800; // MPP=M, MIE=0
            mie <= 64'h0000_0000_0000_0800;     // machine external IRQ enabled
            mtvec <= {IRQ_VECTOR[63:3], 1'b0, 2'b00};
            mepc <= 64'd0;
            mcause <= 64'd0;
            mtval <= 64'd0;
            mscratch <= 64'd0;
            satp <= 64'd0;
            cycle_counter <= 64'd0;
            instret_counter <= 64'd0;
            privilege_mode <= PRIV_M;
            in_interrupt <= 1'b0;
            irq_drain_pending <= 1'b0;
            front_stopped <= 1'b0;
            terminal_pending <= 1'b0;

            ifid_valid <= 1'b0;
            ifid_pc <= 64'd0;
            ifid_instruction <= 64'd0;
            ifid_fault_code <= FAULT_NONE;
            ifid_fault_cause <= 64'd0;
            ifid_fault_address <= 64'd0;
            idex_valid <= 1'b0;
            idex_pc <= 64'd0;
            idex_instruction <= 64'd0;
            idex_fault_code <= FAULT_NONE;
            idex_fault_cause <= 64'd0;
            idex_fault_address <= 64'd0;

            exmem_valid <= 1'b0;
            exmem_pc <= 64'd0;
            exmem_instruction <= 64'd0;
            exmem_rd <= 5'd0;
            exmem_reg_write <= 1'b0;
            exmem_result <= 128'd0;
            exmem_address <= 64'd0;
            exmem_virtual_address <= 64'd0;
            exmem_store_data <= 128'd0;
            exmem_mem_read <= 1'b0;
            exmem_mem_write <= 1'b0;
            exmem_gpio_write <= 1'b0;
            exmem_gpio_data <= 128'd0;
            exmem_ei <= 1'b0;
            exmem_di <= 1'b0;
            exmem_iret <= 1'b0;
            exmem_halt <= 1'b0;
            exmem_fence <= 1'b0;
            exmem_exception <= 1'b0;
            exmem_fault_code <= FAULT_NONE;
            exmem_mcause <= 64'd0;
            exmem_mtval <= 64'd0;
            exmem_csr_write <= 1'b0;
            exmem_csr_address <= 12'd0;
            exmem_csr_wdata <= 64'd0;

            memwb_valid <= 1'b0;
            memwb_pc <= 64'd0;
            memwb_instruction <= 64'd0;
            memwb_rd <= 5'd0;
            memwb_reg_write <= 1'b0;
            memwb_result <= 128'd0;
            memwb_gpio_write <= 1'b0;
            memwb_gpio_data <= 128'd0;
            memwb_ei <= 1'b0;
            memwb_di <= 1'b0;
            memwb_iret <= 1'b0;
            memwb_halt <= 1'b0;
            memwb_fence <= 1'b0;
            memwb_exception <= 1'b0;
            memwb_fault_code <= FAULT_NONE;
            memwb_mcause <= 64'd0;
            memwb_mtval <= 64'd0;
            memwb_csr_write <= 1'b0;
            memwb_csr_address <= 12'd0;
            memwb_csr_wdata <= 64'd0;

            gpio_out <= 128'd0;
            halted <= 1'b0;
            fault <= 1'b0;
            fault_code <= FAULT_NONE;
            instruction_flush <= 1'b0;
            mmu_flush <= 1'b0;
            for (register_index = 0; register_index < 32; register_index = register_index + 1) begin
                registers[register_index] <= 128'd0;
            end
        end else begin
            registers[0] <= 128'd0;
            memwb_valid <= 1'b0;
            instruction_flush <= 1'b0;
            mmu_flush <= 1'b0;
            cycle_counter <= cycle_counter + 64'd1;
            if (memwb_valid && !memwb_exception) instret_counter <= instret_counter + 64'd1;

            // Retire the previous MEM/WB instruction exactly once.
            if (memwb_valid) begin
                if (memwb_reg_write && (memwb_rd != 5'd0)) begin
                    registers[memwb_rd] <= memwb_result;
                end
                if (memwb_gpio_write) gpio_out <= memwb_gpio_data;
                if (memwb_ei) mstatus[3] <= 1'b1;
                if (memwb_di) mstatus[3] <= 1'b0;
                if (memwb_csr_write) begin
                    case (memwb_csr_address)
                        CSR_MSTATUS: begin
                            mstatus[3] <= memwb_csr_wdata[3];
                            mstatus[7] <= memwb_csr_wdata[7];
                            if ((memwb_csr_wdata[12:11] == PRIV_U) ||
                                (memwb_csr_wdata[12:11] == PRIV_M)) begin
                                mstatus[12:11] <= memwb_csr_wdata[12:11];
                            end else begin
                                mstatus[12:11] <= PRIV_M;
                            end
                        end
                        CSR_MIE: mie[11] <= memwb_csr_wdata[11];
                        CSR_MTVEC: begin
                            if (memwb_csr_wdata[1:0] == 2'b01) begin
                                mtvec <= {memwb_csr_wdata[63:3], 1'b0, 2'b01};
                            end else begin
                                mtvec <= {memwb_csr_wdata[63:3], 1'b0, 2'b00};
                            end
                        end
                        CSR_MSCRATCH: mscratch <= memwb_csr_wdata;
                        CSR_MEPC: mepc <= {memwb_csr_wdata[63:3], 3'b000};
                        CSR_MCAUSE: mcause <= memwb_csr_wdata;
                        CSR_MTVAL: mtval <= memwb_csr_wdata;
                        CSR_SATP: begin
                            // Custom SATP layout: MODE[63:60], reserved[59:40],
                            // and a 40-bit root PPN. Only Bare and Sv48 are implemented.
                            if (memwb_csr_wdata[63:60] == 4'd9) begin
                                satp <= {4'd9, 20'd0, memwb_csr_wdata[39:0]};
                            end else begin
                                satp <= 64'd0;
                            end
                        end
                        default: begin end
                    endcase
                end
                if (memwb_iret) begin
                    in_interrupt <= 1'b0;
                    privilege_mode <= mstatus[12:11];
                    mstatus[3] <= mstatus[7];
                    mstatus[7] <= 1'b1;
                    mstatus[12:11] <= PRIV_U;
                    fetch_pc <= mepc;
                    irq_drain_pending <= 1'b0;
                    front_stopped <= 1'b0;
                end
                if (memwb_exception) begin
                    fault <= 1'b1;
                    fault_code <= memwb_fault_code;
                end else if (memwb_halt) begin
                    halted <= 1'b1;
                end else if (memwb_fence) begin
                    // Serialization drained older memory operations. Restart at
                    // the instruction after the fence, not the flushed prefetch.
                    fetch_pc <= memwb_pc + 64'd8;
                    if (!irq_drain_pending && !irq_start) front_stopped <= 1'b0;
                    ifid_valid <= 1'b0;
                end
            end

            if (irq_start) begin
                irq_drain_pending <= 1'b1;
                front_stopped <= 1'b1;
            end
            if (irq_drain_pending && (!irq_can_enter || terminal_pending)) begin
                irq_drain_pending <= 1'b0;
                front_stopped <= 1'b0;
            end

            if (memwb_valid && memwb_exception) begin
                // Precise synchronous trap retirement. Older instructions have
                // committed; redirect to MTVEC instead of halting the processor.
                mepc <= memwb_pc;
                mcause <= memwb_mcause;
                mtval <= memwb_mtval;
                mstatus[7] <= mstatus[3];
                mstatus[3] <= 1'b0;
                mstatus[12:11] <= privilege_mode;
                privilege_mode <= PRIV_M;
                in_interrupt <= 1'b1;
                fetch_pc <= make_trap_target(mtvec, 1'b0, memwb_mcause);
                irq_drain_pending <= 1'b0;
                front_stopped <= 1'b0;
                terminal_pending <= 1'b0;
                ifid_valid <= 1'b0;
                idex_valid <= 1'b0;
                exmem_valid <= 1'b0;
            end else if (halted) begin
                ifid_valid <= 1'b0;
                idex_valid <= 1'b0;
                exmem_valid <= 1'b0;
            end else if (exmem_data_fault) begin
                // The older MEM/WB instruction retires above. The bus-faulting
                // operation is converted to an exception token; younger work dies.
                memwb_valid <= 1'b1;
                memwb_pc <= exmem_pc;
                memwb_instruction <= exmem_instruction;
                memwb_rd <= 5'd0;
                memwb_reg_write <= 1'b0;
                memwb_result <= 128'd0;
                memwb_gpio_write <= 1'b0;
                memwb_gpio_data <= 128'd0;
                memwb_ei <= 1'b0;
                memwb_di <= 1'b0;
                memwb_iret <= 1'b0;
                memwb_halt <= 1'b0;
                memwb_fence <= 1'b0;
                    memwb_exception <= 1'b1;
                memwb_fault_code <= FAULT_DATA_BUS;
                memwb_mcause <= exmem_mem_read ? 64'd5 : 64'd7;
                memwb_mtval <= exmem_virtual_address;
                memwb_csr_write <= 1'b0;
                memwb_csr_address <= 12'd0;
                memwb_csr_wdata <= 64'd0;
                exmem_valid <= 1'b0;
                idex_valid <= 1'b0;
                ifid_valid <= 1'b0;
                irq_drain_pending <= 1'b0;
                front_stopped <= 1'b1;
                terminal_pending <= 1'b1;
            end else if (exmem_memory_wait) begin
                // Hold EX/MEM and all younger stages until the one outstanding
                // memory transaction is accepted. Older WB still retires once.
            end else if (irq_take) begin
                mepc <= fetch_pc;
                mcause <= 64'h8000_0000_0000_000B; // machine external interrupt
                mtval <= 64'd0;
                mstatus[7] <= mstatus[3];
                mstatus[3] <= 1'b0;
                mstatus[12:11] <= privilege_mode;
                privilege_mode <= PRIV_M;
                fetch_pc <= make_trap_target(mtvec, 1'b1, 64'd11);
                in_interrupt <= 1'b1;
                irq_drain_pending <= 1'b0;
                front_stopped <= 1'b0;
                ifid_valid <= 1'b0;
                idex_valid <= 1'b0;
                exmem_valid <= 1'b0;
            end else if (idex_valid && !load_use_stall && ex_exception) begin
                // Precise synchronous exception: older EX/MEM advances, the
                // exception token follows it, and all younger work is flushed.
                if (exmem_valid) begin
                    memwb_valid <= 1'b1;
                    memwb_pc <= exmem_pc;
                    memwb_instruction <= exmem_instruction;
                    memwb_rd <= exmem_rd;
                    memwb_reg_write <= exmem_reg_write;
                    memwb_result <= (exmem_mem_read) ? data_read_data : exmem_result;
                    memwb_gpio_write <= exmem_gpio_write;
                    memwb_gpio_data <= exmem_gpio_data;
                    memwb_ei <= exmem_ei;
                    memwb_di <= exmem_di;
                    memwb_iret <= exmem_iret;
                    memwb_halt <= exmem_halt;
                    memwb_fence <= exmem_fence;
                    memwb_exception <= exmem_exception;
                    memwb_fault_code <= exmem_fault_code;
                    memwb_mcause <= exmem_mcause;
                    memwb_mtval <= exmem_mtval;
                    memwb_csr_write <= exmem_csr_write;
                    memwb_csr_address <= exmem_csr_address;
                    memwb_csr_wdata <= exmem_csr_wdata;
                end
                exmem_valid <= 1'b1;
                exmem_pc <= idex_pc;
                exmem_instruction <= idex_instruction;
                exmem_rd <= 5'd0;
                exmem_reg_write <= 1'b0;
                exmem_result <= 128'd0;
                exmem_address <= 64'd0;
                exmem_virtual_address <= 64'd0;
                exmem_store_data <= 128'd0;
                exmem_mem_read <= 1'b0;
                exmem_mem_write <= 1'b0;
                exmem_gpio_write <= 1'b0;
                exmem_gpio_data <= 128'd0;
                exmem_ei <= 1'b0;
                exmem_di <= 1'b0;
                exmem_iret <= 1'b0;
                exmem_halt <= 1'b0;
                exmem_fence <= 1'b0;
                    exmem_exception <= 1'b1;
                exmem_fault_code <= ex_fault_code;
                exmem_mcause <= ex_mcause;
                exmem_mtval <= ex_mtval;
                exmem_csr_write <= 1'b0;
                exmem_csr_address <= 12'd0;
                exmem_csr_wdata <= 64'd0;
                idex_valid <= 1'b0;
                ifid_valid <= 1'b0;
                irq_drain_pending <= 1'b0;
                front_stopped <= 1'b1;
                terminal_pending <= 1'b1;
            end else if (idex_valid && !load_use_stall && (ex_halt || ex_iret || ex_fence)) begin
                // HALT, returns, and fences serialize the pipe so older effects
                // retire before stopping or redirecting the frontend.
                if (exmem_valid) begin
                    memwb_valid <= 1'b1;
                    memwb_pc <= exmem_pc;
                    memwb_instruction <= exmem_instruction;
                    memwb_rd <= exmem_rd;
                    memwb_reg_write <= exmem_reg_write;
                    memwb_result <= (exmem_mem_read) ? data_read_data : exmem_result;
                    memwb_gpio_write <= exmem_gpio_write;
                    memwb_gpio_data <= exmem_gpio_data;
                    memwb_ei <= exmem_ei;
                    memwb_di <= exmem_di;
                    memwb_iret <= exmem_iret;
                    memwb_halt <= exmem_halt;
                    memwb_fence <= exmem_fence;
                    memwb_exception <= exmem_exception;
                    memwb_fault_code <= exmem_fault_code;
                    memwb_mcause <= exmem_mcause;
                    memwb_mtval <= exmem_mtval;
                    memwb_csr_write <= exmem_csr_write;
                    memwb_csr_address <= exmem_csr_address;
                    memwb_csr_wdata <= exmem_csr_wdata;
                end
                exmem_valid <= 1'b1;
                exmem_pc <= idex_pc;
                exmem_instruction <= idex_instruction;
                exmem_rd <= 5'd0;
                exmem_reg_write <= 1'b0;
                exmem_result <= 128'd0;
                exmem_address <= 64'd0;
                exmem_virtual_address <= 64'd0;
                exmem_store_data <= 128'd0;
                exmem_mem_read <= 1'b0;
                exmem_mem_write <= 1'b0;
                exmem_gpio_write <= 1'b0;
                exmem_gpio_data <= 128'd0;
                exmem_ei <= 1'b0;
                exmem_di <= 1'b0;
                exmem_iret <= ex_iret;
                exmem_halt <= ex_halt;
                exmem_fence <= ex_fence;
                exmem_exception <= 1'b0;
                exmem_fault_code <= FAULT_NONE;
                exmem_mcause <= 64'd0;
                exmem_mtval <= 64'd0;
                exmem_csr_write <= ex_csr_write;
                exmem_csr_address <= ex_csr_address;
                exmem_csr_wdata <= ex_csr_wdata;
                idex_valid <= 1'b0;
                ifid_valid <= 1'b0;
                front_stopped <= 1'b1;
                if (ex_fence_i) instruction_flush <= 1'b1;
                if (ex_mmu_flush) mmu_flush <= 1'b1;
                if (ex_halt) begin
                    terminal_pending <= 1'b1;
                    irq_drain_pending <= 1'b0;
                end
            end else if (csr_read_stall || data_translation_stall) begin
                // CSR reads wait for older writes; translated data operations
                // hold EX until the MMU returns a physical address or fault.
                if (exmem_valid) begin
                    memwb_valid <= 1'b1;
                    memwb_pc <= exmem_pc;
                    memwb_instruction <= exmem_instruction;
                    memwb_rd <= exmem_rd;
                    memwb_reg_write <= exmem_reg_write;
                    memwb_result <= exmem_mem_read ? data_read_data : exmem_result;
                    memwb_gpio_write <= exmem_gpio_write;
                    memwb_gpio_data <= exmem_gpio_data;
                    memwb_ei <= exmem_ei;
                    memwb_di <= exmem_di;
                    memwb_iret <= exmem_iret;
                    memwb_halt <= exmem_halt;
                    memwb_fence <= exmem_fence;
                    memwb_exception <= exmem_exception;
                    memwb_fault_code <= exmem_fault_code;
                    memwb_mcause <= exmem_mcause;
                    memwb_mtval <= exmem_mtval;
                    memwb_csr_write <= exmem_csr_write;
                    memwb_csr_address <= exmem_csr_address;
                    memwb_csr_wdata <= exmem_csr_wdata;
                end
                exmem_valid <= 1'b0;
                exmem_mem_read <= 1'b0;
                exmem_mem_write <= 1'b0;
            end else if (load_use_stall) begin
                // Bubble EX while preserving the dependent instruction and the
                // fetch queue. The load advances into MEM/WB for next-cycle bypass.
                if (exmem_valid) begin
                    memwb_valid <= 1'b1;
                    memwb_pc <= exmem_pc;
                    memwb_instruction <= exmem_instruction;
                    memwb_rd <= exmem_rd;
                    memwb_reg_write <= exmem_reg_write;
                    memwb_result <= data_read_data;
                    memwb_gpio_write <= exmem_gpio_write;
                    memwb_gpio_data <= exmem_gpio_data;
                    memwb_ei <= exmem_ei;
                    memwb_di <= exmem_di;
                    memwb_iret <= exmem_iret;
                    memwb_halt <= exmem_halt;
                    memwb_fence <= exmem_fence;
                    memwb_exception <= exmem_exception;
                    memwb_fault_code <= exmem_fault_code;
                    memwb_mcause <= exmem_mcause;
                    memwb_mtval <= exmem_mtval;
                    memwb_csr_write <= exmem_csr_write;
                    memwb_csr_address <= exmem_csr_address;
                    memwb_csr_wdata <= exmem_csr_wdata;
                end
                exmem_valid <= 1'b0;
                exmem_mem_read <= 1'b0;
                exmem_mem_write <= 1'b0;
            end else begin
                // Normal MEM -> WB transfer.
                if (exmem_valid) begin
                    memwb_valid <= 1'b1;
                    memwb_pc <= exmem_pc;
                    memwb_instruction <= exmem_instruction;
                    memwb_rd <= exmem_rd;
                    memwb_reg_write <= exmem_reg_write;
                    memwb_result <= (exmem_mem_read) ? data_read_data : exmem_result;
                    memwb_gpio_write <= exmem_gpio_write;
                    memwb_gpio_data <= exmem_gpio_data;
                    memwb_ei <= exmem_ei;
                    memwb_di <= exmem_di;
                    memwb_iret <= exmem_iret;
                    memwb_halt <= exmem_halt;
                    memwb_fence <= exmem_fence;
                    memwb_exception <= exmem_exception;
                    memwb_fault_code <= exmem_fault_code;
                    memwb_mcause <= exmem_mcause;
                    memwb_mtval <= exmem_mtval;
                    memwb_csr_write <= exmem_csr_write;
                    memwb_csr_address <= exmem_csr_address;
                    memwb_csr_wdata <= exmem_csr_wdata;
                end

                // EX -> MEM. Exceptions are handled above; bubbles stay bubbles.
                exmem_valid <= idex_valid;
                exmem_pc <= idex_pc;
                exmem_instruction <= idex_instruction;
                exmem_rd <= ex_destination;
                exmem_reg_write <= ex_reg_write;
                exmem_result <= ex_result;
                exmem_address <= ((idex_opcode == OP_LOAD) || (idex_opcode == OP_STORE)) ?
                    data_translation_address : ex_effective_address;
                exmem_virtual_address <= ex_effective_address;
                exmem_store_data <= ex_operand_b_register;
                exmem_mem_read <= ex_mem_read;
                exmem_mem_write <= ex_mem_write;
                exmem_gpio_write <= ex_gpio_write;
                exmem_gpio_data <= ex_gpio_data;
                exmem_ei <= ex_ei;
                exmem_di <= ex_di;
                exmem_iret <= ex_iret;
                exmem_halt <= ex_halt;
                exmem_fence <= ex_fence;
                exmem_exception <= 1'b0;
                exmem_fault_code <= FAULT_NONE;
                exmem_mcause <= 64'd0;
                exmem_mtval <= 64'd0;
                exmem_csr_write <= ex_csr_write;
                exmem_csr_address <= ex_csr_address;
                exmem_csr_wdata <= ex_csr_wdata;

                if (ex_redirect) begin
                    fetch_pc <= ex_redirect_target;
                    idex_valid <= 1'b0;
                    ifid_valid <= 1'b0;
                end else begin
                    idex_valid <= ifid_valid;
                    idex_pc <= ifid_pc;
                    idex_instruction <= ifid_instruction;
                    idex_fault_code <= ifid_fault_code;
                    idex_fault_cause <= ifid_fault_cause;
                    idex_fault_address <= ifid_fault_address;

                    if (!front_stopped && !irq_start && instruction_ready) begin
                        ifid_valid <= 1'b1;
                        ifid_pc <= fetch_pc;
                        ifid_instruction <= instruction;
                        ifid_fault_code <= instruction_error ?
                            (((instruction_fault_cause == 64'd12)) ? FAULT_PAGE : FAULT_FETCH_RANGE) : FAULT_NONE;
                        ifid_fault_cause <= instruction_error ? instruction_fault_cause : 64'd0;
                        ifid_fault_address <= instruction_error ? instruction_fault_address : 64'd0;
                        fetch_pc <= fetch_pc + 64'd8;
                    end else begin
                        // A cache miss freezes only the frontend; older stages
                        // continue retiring while fetch_pc holds its address.
                        ifid_valid <= 1'b0;
                    end
                end
            end
        end
    end
endmodule
