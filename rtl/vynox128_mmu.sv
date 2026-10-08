// Standalone four-level Sv48-inspired (but custom-ISA) translation unit.
// The SoC does not yet instantiate this block; see VM_SPEC.md for integration work.
`timescale 1ns/1ps
module vynox128_mmu #(
    parameter integer TLB_ENTRIES = 4
) (
    input  wire         clk,
    input  wire         reset,

    input  wire         request_valid,
    input  wire [63:0]  virtual_address,
    input  wire [1:0]   privilege_mode,
    input  wire [1:0]   access_type, // 0=read, 1=write, 2=execute
    input  wire         vm_enable,
    input  wire [39:0]  root_ppn,
    input  wire         tlb_flush,
    output logic        request_ready,
    output logic [63:0] physical_address,
    output logic        request_fault,
    output logic [63:0] fault_cause,
    output logic [63:0] fault_address,

    output logic        ptw_valid,
    output logic [63:0] ptw_address,
    input  wire         ptw_ready,
    input  wire         ptw_error,
    input  wire [63:0]  ptw_read_data
);
    localparam logic [1:0] PRIV_U = 2'b00;
    localparam logic [1:0] PRIV_M = 2'b11;
    localparam logic [1:0] ACCESS_READ = 2'd0;
    localparam logic [1:0] ACCESS_WRITE = 2'd1;
    localparam logic [1:0] ACCESS_EXECUTE = 2'd2;

    localparam logic [1:0] STATE_IDLE = 2'd0;
    localparam logic [1:0] STATE_WALK = 2'd1;
    localparam logic [1:0] STATE_RESP = 2'd2;

    localparam integer INDEX_BITS = $clog2(TLB_ENTRIES);
    localparam integer VPN_BITS = 36;

    logic [1:0] state;
    logic [TLB_ENTRIES-1:0] tlb_valid;
    logic [VPN_BITS-1:0] tlb_vpn [0:TLB_ENTRIES-1];
    logic [39:0] tlb_root_ppn [0:TLB_ENTRIES-1];
    logic [39:0] tlb_ppn [0:TLB_ENTRIES-1];
    logic [5:0] tlb_permissions [0:TLB_ENTRIES-1];

    logic [63:0] walk_va;
    logic [39:0] walk_root_ppn;
    logic [39:0] walk_table_ppn;
    logic [1:0] walk_level;
    logic [1:0] walk_access_type;
    logic [INDEX_BITS-1:0] walk_tlb_index;
    logic discard_walk;

    logic response_fault;
    logic [63:0] response_cause;
    logic [63:0] response_va;
    logic [1:0] response_access_type;

    wire [VPN_BITS-1:0] request_vpn = virtual_address[47:12];
    wire [INDEX_BITS-1:0] request_tlb_index = virtual_address[12 +: INDEX_BITS];
    wire request_canonical = (virtual_address[63:48] == {16{virtual_address[47]}});
    wire tlb_hit = !tlb_flush && tlb_valid[request_tlb_index] &&
        (tlb_vpn[request_tlb_index] == request_vpn) &&
        (tlb_root_ppn[request_tlb_index] == root_ppn);
    wire [8:0] walk_index = vpn_index(walk_va, walk_level);
    wire [63:0] current_pte_address =
        {12'd0, walk_table_ppn, 12'd0} + ({55'd0, walk_index} << 3);

    integer entry_index;

    initial begin
        if ((TLB_ENTRIES < 2) || ((TLB_ENTRIES & (TLB_ENTRIES - 1)) != 0)) begin
            $fatal(1, "vynox128_mmu TLB_ENTRIES must be a power of two >= 2");
        end
    end

    function automatic logic [8:0] vpn_index(
        input logic [63:0] address_value,
        input logic [1:0] level_value
    );
        begin
            case (level_value)
                2'd3: vpn_index = address_value[47:39];
                2'd2: vpn_index = address_value[38:30];
                2'd1: vpn_index = address_value[29:21];
                default: vpn_index = address_value[20:12];
            endcase
        end
    endfunction

    function automatic logic [63:0] page_fault_code(input logic [1:0] access_value);
        begin
            case (access_value)
                ACCESS_EXECUTE: page_fault_code = 64'd12;
                ACCESS_WRITE:   page_fault_code = 64'd15;
                default:        page_fault_code = 64'd13;
            endcase
        end
    endfunction

    function automatic logic [63:0] access_fault_code(input logic [1:0] access_value);
        begin
            case (access_value)
                ACCESS_EXECUTE: access_fault_code = 64'd1;
                ACCESS_WRITE:   access_fault_code = 64'd7;
                default:        access_fault_code = 64'd5;
            endcase
        end
    endfunction

    function automatic logic pte_permissions_allow(
        input logic [5:0] permissions,
        input logic [1:0] access_value
    );
        begin
            pte_permissions_allow = 1'b0;
            if (permissions[3] && permissions[4]) begin // U and A
                case (access_value)
                    ACCESS_READ:    pte_permissions_allow = permissions[0]; // R
                    ACCESS_WRITE:   pte_permissions_allow = permissions[1] && permissions[5]; // W and D
                    ACCESS_EXECUTE: pte_permissions_allow = permissions[2]; // X
                    default:        pte_permissions_allow = 1'b0;
                endcase
            end
        end
    endfunction

    function automatic logic pte_reserved_bits_clear(input logic [63:0] pte_value);
        begin
            pte_reserved_bits_clear = (pte_value[63:52] == 12'd0) &&
                                      (pte_value[11:7] == 5'd0);
        end
    endfunction

    always @* begin
        request_ready = 1'b0;
        physical_address = 64'd0;
        request_fault = 1'b0;
        fault_cause = 64'd0;
        fault_address = virtual_address;
        ptw_valid = (state == STATE_WALK);
        ptw_address = current_pte_address;

        if (request_valid) begin
            if ((privilege_mode == PRIV_M) || !vm_enable) begin
                // Machine mode and bare mode preserve the existing physical path.
                request_ready = 1'b1;
                physical_address = virtual_address;
            end else if (privilege_mode != PRIV_U) begin
                request_ready = 1'b1;
                request_fault = 1'b1;
                fault_cause = page_fault_code(access_type);
            end else if (!request_canonical) begin
                request_ready = 1'b1;
                request_fault = 1'b1;
                fault_cause = page_fault_code(access_type);
            end else if (tlb_hit) begin
                request_ready = 1'b1;
                if (pte_permissions_allow(tlb_permissions[request_tlb_index], access_type)) begin
                    physical_address = {12'd0, tlb_ppn[request_tlb_index], virtual_address[11:0]};
                end else begin
                    request_fault = 1'b1;
                    fault_cause = page_fault_code(access_type);
                end
            end else if ((state == STATE_RESP) &&
                         (virtual_address == response_va) &&
                         (root_ppn == walk_root_ppn) &&
                         (access_type == response_access_type)) begin
                request_ready = 1'b1;
                request_fault = response_fault;
                fault_cause = response_cause;
                if (!response_fault) begin
                    // Successful walks normally install a TLB entry and return
                    // through the hit path; this path is reserved for responses.
                    physical_address = {12'd0, tlb_ppn[walk_tlb_index], virtual_address[11:0]};
                end
            end
        end
    end

    always_ff @(posedge clk) begin
        if (reset) begin
            state <= STATE_IDLE;
            tlb_valid <= '0;
            walk_va <= 64'd0;
            walk_root_ppn <= 40'd0;
            walk_table_ppn <= 40'd0;
            walk_level <= 2'd0;
            walk_access_type <= ACCESS_READ;
            walk_tlb_index <= '0;
            discard_walk <= 1'b0;
            response_fault <= 1'b0;
            response_cause <= 64'd0;
            response_va <= 64'd0;
            response_access_type <= ACCESS_READ;
            for (entry_index = 0; entry_index < TLB_ENTRIES; entry_index = entry_index + 1) begin
                tlb_vpn[entry_index] <= '0;
                tlb_root_ppn[entry_index] <= 40'd0;
                tlb_ppn[entry_index] <= 40'd0;
                tlb_permissions[entry_index] <= 6'd0;
            end
        end else begin
            if (tlb_flush) begin
                tlb_valid <= '0;
                if ((state == STATE_WALK) && !ptw_ready) begin
                    discard_walk <= 1'b1;
                end else begin
                    discard_walk <= 1'b0;
                end
            end

            case (state)
                STATE_IDLE: begin
                    if (request_valid && vm_enable && (privilege_mode == PRIV_U) &&
                        request_canonical && !tlb_hit && !tlb_flush) begin
                        walk_va <= virtual_address;
                        walk_root_ppn <= root_ppn;
                        walk_table_ppn <= root_ppn;
                        walk_level <= 2'd3;
                        walk_access_type <= access_type;
                        walk_tlb_index <= request_tlb_index;
                        discard_walk <= 1'b0;
                        state <= STATE_WALK;
                    end
                end

                STATE_WALK: begin
                    if (ptw_ready) begin
                        if (ptw_error) begin
                            response_fault <= 1'b1;
                            response_cause <= access_fault_code(walk_access_type);
                            response_va <= walk_va;
                            response_access_type <= walk_access_type;
                            state <= STATE_RESP;
                            discard_walk <= 1'b0;
                        end else if (!ptw_read_data[0] || !pte_reserved_bits_clear(ptw_read_data)) begin
                            response_fault <= 1'b1;
                            response_cause <= page_fault_code(walk_access_type);
                            response_va <= walk_va;
                            response_access_type <= walk_access_type;
                            state <= STATE_RESP;
                            discard_walk <= 1'b0;
                        end else if (|ptw_read_data[3:1]) begin
                            if ((walk_level != 2'd0) || (ptw_read_data[2] && !ptw_read_data[1]) ||
                                !pte_permissions_allow(ptw_read_data[6:1], walk_access_type)) begin
                                response_fault <= 1'b1;
                                response_cause <= page_fault_code(walk_access_type);
                                response_va <= walk_va;
                                response_access_type <= walk_access_type;
                                state <= STATE_RESP;
                            end else if (!discard_walk && !tlb_flush) begin
                                tlb_valid[walk_tlb_index] <= 1'b1;
                                tlb_vpn[walk_tlb_index] <= walk_va[47:12];
                                tlb_root_ppn[walk_tlb_index] <= walk_root_ppn;
                                tlb_ppn[walk_tlb_index] <= ptw_read_data[51:12];
                                tlb_permissions[walk_tlb_index] <= ptw_read_data[6:1];
                                state <= STATE_IDLE;
                            end else begin
                                state <= STATE_IDLE;
                            end
                            discard_walk <= 1'b0;
                        end else if ((walk_level == 2'd0) || (ptw_read_data[6:1] != 6'd0)) begin
                            response_fault <= 1'b1;
                            response_cause <= page_fault_code(walk_access_type);
                            response_va <= walk_va;
                            response_access_type <= walk_access_type;
                            state <= STATE_RESP;
                            discard_walk <= 1'b0;
                        end else begin
                            walk_table_ppn <= ptw_read_data[51:12];
                            walk_level <= walk_level - 2'd1;
                            if (tlb_flush || discard_walk) begin
                                // Continue draining the read but never install its translation.
                                discard_walk <= 1'b1;
                            end
                        end
                    end
                end

                STATE_RESP: begin
                    // A precise page/access fault belongs only to the original
                    // fetch/load/store. Redirects discard it instead of faulting
                    // the replacement request with stale metadata.
                    if (tlb_flush || !request_valid ||
                        (virtual_address != response_va) ||
                        (root_ppn != walk_root_ppn) ||
                        (access_type != response_access_type) || request_ready) begin
                        state <= STATE_IDLE;
                    end
                end

                default: state <= STATE_IDLE;
            endcase
        end
    end
endmodule
