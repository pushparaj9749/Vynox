// Vynox-128 SoC: pipelined CPU, L1D, wait-state RAM, MMIO, timer IRQ, and full-duplex UART.
`timescale 1ns/1ps
module vynox128_soc #(
    parameter PROGRAM_FILE = "",
    parameter integer MEMORY_WAIT_CYCLES = 2,
    parameter integer UART_DIVISOR = 16,
    parameter integer DCACHE_LINES = 16,
    parameter integer ICACHE_LINES = 16,
    parameter integer TLB_ENTRIES = 4,
    parameter integer INSTRUCTION_WAIT_CYCLES = 1,
    parameter integer RAM_BYTES = 32768,
    parameter logic [63:0] USER_RAM_BASE = 64'h0000_0000_0000_0000,
    parameter logic [63:0] USER_RAM_LIMIT = 64'h0000_0000_0000_0800
) (
    input  wire         clk,
    input  wire         reset,
    input  wire [127:0] gpio_in,
    input  wire         uart_rx,
    output wire         uart_tx,
    output wire [127:0] gpio_out,
    output wire         timer_irq,
    output wire         halted,
    output wire         fault,
    output wire [7:0]   fault_code
);
    // The low 4 KiB retains its original MMIO map; ordinary RAM extends to
    // RAM_BYTES so page tables can occupy full 4 KiB frames above that window.
    localparam logic [11:0] UART_RX_ADDR       = 12'hF70;
    localparam logic [11:0] UART_TX_ADDR       = 12'hF80;
    localparam logic [11:0] UART_STATUS_ADDR   = 12'hF90;
    localparam logic [11:0] UART_BAUD_ADDR     = 12'hFA0;
    localparam logic [11:0] UART_CONTROL_ADDR  = 12'hFB0;
    localparam logic [11:0] TIMER_CONTROL_ADDR = 12'hFC0;
    localparam logic [11:0] TIMER_COUNT_ADDR   = 12'hFD0;
    localparam logic [11:0] TIMER_COMPARE_ADDR = 12'hFE0;
    localparam logic [11:0] TIMER_STATUS_ADDR  = 12'hFF0;
    localparam integer RAM_WORDS = RAM_BYTES / 16;
    localparam logic [11:0] MMIO_BASE_ADDR = 12'hF70;
    localparam logic [31:0] UART_DIVISOR_RESET = (UART_DIVISOR > 0) ? UART_DIVISOR : 1;

    logic [63:0] instruction_rom [0:255]; // 256 x 64-bit words (2 KiB)
    logic [127:0] data_ram [0:RAM_WORDS-1];

    wire [63:0] instruction_address; // virtual PC emitted by the core
    wire instruction_error;
    wire [63:0] instruction_fault_cause;
    wire [63:0] instruction_fault_address;
    logic [63:0] instruction;
    wire [63:0] instruction_physical_address;
    wire instruction_translation_ready;
    wire instruction_translation_fault;
    wire [63:0] instruction_translation_fault_cause;
    wire [63:0] instruction_translation_fault_address;
    wire instruction_ptw_valid;
    wire [63:0] instruction_ptw_address;
    wire instruction_ptw_ready;
    wire instruction_ptw_error;
    wire [63:0] instruction_ptw_read_data;
    wire core_instruction_ready;
    wire core_instruction_flush;
    wire core_mmu_flush;
    wire [63:0] core_satp_value;
    wire icache_cpu_valid;
    wire icache_cpu_ready;
    wire icache_cpu_error;
    wire icache_memory_valid;
    wire [63:0] icache_memory_address;
    wire icache_memory_ready;
    wire icache_memory_error;
    logic [63:0] icache_memory_read_data;
    wire [1:0] core_privilege_mode;
    wire core_ex_data_request;
    wire core_ex_data_write;
    wire [63:0] core_ex_data_address;
    wire core_data_access_allowed;
    wire data_translation_ready;
    wire [63:0] data_translation_address;
    wire data_translation_fault;
    wire [63:0] data_translation_fault_cause;
    wire [63:0] data_translation_fault_address;
    wire data_ptw_valid;
    wire [63:0] data_ptw_address;
    wire data_ptw_ready;
    wire data_ptw_error;
    wire [63:0] data_ptw_read_data;
    wire core_data_valid;
    wire core_data_write_enable;
    wire [63:0] core_data_address;
    wire [127:0] core_data_write_data;
    logic [127:0] core_data_read_data;
    wire memory_ready; // CPU-side cache response
    wire memory_error;
    wire backing_valid;
    wire backing_write_enable;
    wire [63:0] backing_address;
    wire [127:0] backing_write_data;
    wire backing_ready;
    wire backing_error;
    logic [127:0] backing_read_data;

    logic instruction_memory_busy;
    logic [31:0] instruction_wait_count;
    logic [63:0] pending_instruction_address;
    logic pending_instruction_error;

    logic memory_busy;
    logic [31:0] wait_count;
    logic [63:0] pending_address;
    logic pending_write;
    logic pending_error;
    logic [127:0] pending_write_data;

    logic ptw_busy;
    logic ptw_pending_instruction;
    logic [31:0] ptw_wait_count;
    logic [63:0] ptw_pending_address;
    logic ptw_pending_error;
    wire ptw_response_ready = ptw_busy && (ptw_wait_count == 32'd0);
    logic [63:0] ptw_response_read_data;
    wire backing_mmio_region = (backing_address[63:12] == 52'd0) &&
        (backing_address[11:0] >= MMIO_BASE_ADDR);
    wire backing_ram_region = (backing_address < RAM_BYTES) && !backing_mmio_region;
    wire pending_mmio_region = (pending_address[63:12] == 52'd0) &&
        (pending_address[11:0] >= MMIO_BASE_ADDR);
    wire pending_ram_region = (pending_address < RAM_BYTES) && !pending_mmio_region;
    wire core_address_mmio = (core_data_address[63:12] == 52'd0) &&
        (core_data_address[11:0] >= MMIO_BASE_ADDR);
    wire core_data_address_ram = (core_data_address < RAM_BYTES) && !core_address_mmio;
    wire backing_mmio_valid = backing_mmio_region &&
        ((backing_address[11:0] == UART_RX_ADDR) ||
         (backing_address[11:0] == UART_TX_ADDR) ||
         (backing_address[11:0] == UART_STATUS_ADDR) ||
         (backing_address[11:0] == UART_BAUD_ADDR) ||
         (backing_address[11:0] == UART_CONTROL_ADDR) ||
         (backing_address[11:0] == TIMER_CONTROL_ADDR) ||
         (backing_address[11:0] == TIMER_COUNT_ADDR) ||
         (backing_address[11:0] == TIMER_COMPARE_ADDR) ||
         (backing_address[11:0] == TIMER_STATUS_ADDR));
    wire ptw_selected_instruction = instruction_ptw_valid;
    wire [63:0] ptw_selected_address = ptw_selected_instruction ?
        instruction_ptw_address : data_ptw_address;
    wire ptw_selected_ram = (ptw_selected_address < RAM_BYTES) &&
        (ptw_selected_address[63:16] == 48'd0) &&
        !((ptw_selected_address[63:12] == 52'd0) &&
          (ptw_selected_address[11:0] >= MMIO_BASE_ADDR));

    logic timer_enable;
    logic timer_irq_enable;
    logic timer_periodic;
    logic timer_pending;
    logic [63:0] timer_count;
    logic [63:0] timer_compare;

    logic uart_enable;
    logic uart_rx_irq_enable;
    logic [31:0] uart_divisor;
    wire uart_tx_busy;
    wire uart_tx_ready;
    wire [7:0] uart_last_tx;
    wire [7:0] uart_rx_data;
    wire uart_rx_valid;
    wire uart_rx_error;
    wire uart_rx_irq;
    wire uart_tx_valid;
    wire uart_rx_consume;
    wire uart_rx_clear_error;
    wire core_irq;

    integer init_index;

    assign backing_ready = memory_busy && (wait_count == 32'd0) &&
        (pending_error || !(pending_write && (pending_address[11:0] == UART_TX_ADDR) && uart_tx_busy));
    assign backing_error = backing_ready && pending_error;
    assign icache_memory_ready = instruction_memory_busy && (instruction_wait_count == 32'd0);
    assign icache_memory_error = icache_memory_ready && pending_instruction_error;
    assign instruction_ptw_ready = ptw_response_ready && ptw_pending_instruction;
    assign instruction_ptw_error = ptw_pending_error;
    assign instruction_ptw_read_data = ptw_response_read_data;
    assign data_ptw_ready = ptw_response_ready && !ptw_pending_instruction;
    assign data_ptw_error = ptw_pending_error;
    assign data_ptw_read_data = ptw_response_read_data;
    assign icache_cpu_valid = instruction_translation_ready && !instruction_translation_fault;
    assign core_instruction_ready = instruction_translation_ready &&
        (instruction_translation_fault || icache_cpu_ready);
    assign instruction_error = instruction_translation_fault || icache_cpu_error;
    assign instruction_fault_cause = instruction_translation_fault ?
        instruction_translation_fault_cause : (icache_cpu_error ? 64'd1 : 64'd0);
    assign instruction_fault_address = instruction_translation_fault ?
        instruction_translation_fault_address : instruction_address;
    assign timer_irq = timer_pending && timer_irq_enable;
    assign core_irq = timer_irq || uart_rx_irq;
    assign uart_tx_valid = backing_ready && pending_write &&
        (pending_address[11:0] == UART_TX_ADDR) && !pending_error;
    assign uart_rx_consume = backing_ready && !pending_write &&
        (pending_address[11:0] == UART_RX_ADDR) && !pending_error;
    assign uart_rx_clear_error = backing_ready && pending_write &&
        (pending_address[11:0] == UART_STATUS_ADDR) && pending_write_data[4] && !pending_error;

    // I-cache refills read the word-addressed ROM. The D-cache handles only
    // physical RAM; MMIO accesses bypass it and page walks read RAM directly.
    always @* begin
        icache_memory_read_data = 64'd0;
        if (pending_instruction_address[63:11] == 53'd0) begin
            icache_memory_read_data = instruction_rom[pending_instruction_address[10:3]];
        end

        backing_read_data = 128'd0;
        if (backing_address[63:16] == 48'd0) begin
            if (backing_mmio_region) begin
                case (backing_address[11:0])
                    UART_RX_ADDR: begin
                        backing_read_data = {120'd0, uart_rx_data};
                    end
                    UART_TX_ADDR: begin
                        backing_read_data = {120'd0, uart_last_tx};
                    end
                    UART_STATUS_ADDR: begin
                        backing_read_data = {123'd0, uart_rx_error, uart_rx_valid,
                                             uart_enable, uart_tx_ready, uart_tx_busy};
                    end
                    UART_BAUD_ADDR: begin
                        backing_read_data = {96'd0, uart_divisor};
                    end
                    UART_CONTROL_ADDR: begin
                        backing_read_data = {126'd0, uart_rx_irq_enable, uart_enable};
                    end
                    TIMER_CONTROL_ADDR: begin
                        backing_read_data = {125'd0, timer_periodic, timer_irq_enable, timer_enable};
                    end
                    TIMER_COUNT_ADDR: begin
                        backing_read_data = {64'd0, timer_count};
                    end
                    TIMER_COMPARE_ADDR: begin
                        backing_read_data = {64'd0, timer_compare};
                    end
                    TIMER_STATUS_ADDR: begin
                        backing_read_data = {127'd0, timer_pending};
                    end
                    default: backing_read_data = 128'd0;
                endcase
            end else if (backing_ram_region) begin
                backing_read_data = data_ram[backing_address[15:4]];
            end
        end

        ptw_response_read_data = 64'd0;
        if (ptw_response_ready && !ptw_pending_error) begin
            if (ptw_pending_address[3]) begin
                ptw_response_read_data = data_ram[ptw_pending_address[15:4]][127:64];
            end else begin
                ptw_response_read_data = data_ram[ptw_pending_address[15:4]][63:0];
            end
        end
    end

    initial begin
        if ((RAM_BYTES < 4096) || (RAM_BYTES > 65536) || ((RAM_BYTES % 4096) != 0)) begin
            $fatal(1, "vynox128_soc RAM_BYTES must be a 4 KiB multiple from 4096 through 65536");
        end
        for (init_index = 0; init_index < 256; init_index = init_index + 1) begin
            instruction_rom[init_index] = 64'd0;
        end
        for (init_index = 0; init_index < RAM_WORDS; init_index = init_index + 1) begin
            data_ram[init_index] = 128'd0;
        end
        if (PROGRAM_FILE != "") begin
            $readmemh(PROGRAM_FILE, instruction_rom);
        end
    end

    vynox128_mmu #(.TLB_ENTRIES(TLB_ENTRIES)) instruction_mmu (
        .clk(clk), .reset(reset),
        .request_valid(1'b1), .virtual_address(instruction_address),
        .privilege_mode(core_privilege_mode), .access_type(2'd2),
        .vm_enable(core_satp_value[63:60] == 4'd9),
        .root_ppn(core_satp_value[39:0]), .tlb_flush(core_mmu_flush),
        .request_ready(instruction_translation_ready),
        .physical_address(instruction_physical_address),
        .request_fault(instruction_translation_fault),
        .fault_cause(instruction_translation_fault_cause),
        .fault_address(instruction_translation_fault_address),
        .ptw_valid(instruction_ptw_valid), .ptw_address(instruction_ptw_address),
        .ptw_ready(instruction_ptw_ready), .ptw_error(instruction_ptw_error),
        .ptw_read_data(instruction_ptw_read_data)
    );

    vynox128_mmu #(.TLB_ENTRIES(TLB_ENTRIES)) data_mmu (
        .clk(clk), .reset(reset),
        .request_valid(core_ex_data_request), .virtual_address(core_ex_data_address),
        .privilege_mode(core_privilege_mode),
        .access_type(core_ex_data_write ? 2'd1 : 2'd0),
        .vm_enable(core_satp_value[63:60] == 4'd9),
        .root_ppn(core_satp_value[39:0]), .tlb_flush(core_mmu_flush),
        .request_ready(data_translation_ready),
        .physical_address(data_translation_address),
        .request_fault(data_translation_fault),
        .fault_cause(data_translation_fault_cause),
        .fault_address(data_translation_fault_address),
        .ptw_valid(data_ptw_valid), .ptw_address(data_ptw_address),
        .ptw_ready(data_ptw_ready), .ptw_error(data_ptw_error),
        .ptw_read_data(data_ptw_read_data)
    );

    vynox128_mpu #(
        .USER_RAM_BASE(USER_RAM_BASE), .USER_RAM_LIMIT(USER_RAM_LIMIT)
    ) access_guard (
        .request_valid(core_ex_data_request && data_translation_ready && !data_translation_fault),
        .address(data_translation_address), .privilege_mode(core_privilege_mode),
        .allowed(core_data_access_allowed)
    );

    vynox128_icache #(
        .LINES(ICACHE_LINES)
    ) icache (
        .clk(clk), .reset(reset),
        .cpu_valid(icache_cpu_valid), .cpu_cacheable(instruction_physical_address[63:11] == 53'd0),
        .cpu_address(instruction_physical_address), .invalidate(core_instruction_flush),
        .cpu_ready(icache_cpu_ready), .cpu_error(icache_cpu_error),
        .cpu_instruction(instruction),
        .memory_valid(icache_memory_valid), .memory_address(icache_memory_address),
        .memory_ready(icache_memory_ready), .memory_error(icache_memory_error),
        .memory_read_data(icache_memory_read_data)
    );

    vynox128_dcache #(
        .LINES(DCACHE_LINES)
    ) dcache (
        .clk(clk), .reset(reset),
        .cpu_valid(core_data_valid), .cpu_write(core_data_write_enable),
        .cpu_address(core_data_address), .cpu_write_data(core_data_write_data),
        .cpu_cacheable(core_data_address_ram),
        .cpu_ready(memory_ready), .cpu_error(memory_error),
        .cpu_read_data(core_data_read_data),
        .memory_valid(backing_valid), .memory_write(backing_write_enable),
        .memory_address(backing_address), .memory_write_data(backing_write_data),
        .memory_ready(backing_ready), .memory_error(backing_error),
        .memory_read_data(backing_read_data)
    );

    vynox128_uart uart (
        .clk(clk), .reset(reset), .enable(uart_enable), .rx(uart_rx), .tx(uart_tx),
        .divisor(uart_divisor), .tx_valid(uart_tx_valid),
        .tx_data(pending_write_data[7:0]), .tx_ready(uart_tx_ready),
        .tx_busy(uart_tx_busy), .last_tx(uart_last_tx),
        .rx_data(uart_rx_data), .rx_valid(uart_rx_valid),
        .rx_consume(uart_rx_consume), .rx_error(uart_rx_error),
        .rx_clear_error(uart_rx_clear_error), .rx_irq_enable(uart_rx_irq_enable),
        .rx_irq(uart_rx_irq)
    );

    vynox128_core core (
        .clk(clk),
        .reset(reset),
        .irq(core_irq),
        .instruction(instruction),
        .instruction_ready(core_instruction_ready),
        .instruction_error(instruction_error),
        .instruction_fault_cause(instruction_fault_cause),
        .instruction_fault_address(instruction_fault_address),
        .instruction_address(instruction_address),
        .instruction_flush(core_instruction_flush),
        .mmu_flush(core_mmu_flush),
        .satp_value(core_satp_value),
        .data_valid(core_data_valid),
        .data_write_enable(core_data_write_enable),
        .data_address(core_data_address),
        .data_write_data(core_data_write_data),
        .data_access_allowed(core_data_access_allowed),
        .data_translation_ready(data_translation_ready),
        .data_translation_address(data_translation_address),
        .data_translation_fault(data_translation_fault),
        .data_translation_fault_cause(data_translation_fault_cause),
        .data_translation_fault_address(data_translation_fault_address),
        .privilege_mode_out(core_privilege_mode),
        .ex_data_request(core_ex_data_request),
        .ex_data_write(core_ex_data_write),
        .ex_data_address(core_ex_data_address),
        .data_ready(memory_ready),
        .data_error(memory_error),
        .data_read_data(core_data_read_data),
        .gpio_in(gpio_in),
        .gpio_out(gpio_out),
        .halted(halted),
        .fault(fault),
        .fault_code(fault_code)
    );

    // Single outstanding bus request; timer ticks while UART shifts at the
    // system clock. TX stores wait until the serializer can accept a byte.
    always @(posedge clk) begin
        if (reset) begin
            instruction_memory_busy <= 1'b0;
            instruction_wait_count <= 32'd0;
            pending_instruction_address <= 64'd0;
            pending_instruction_error <= 1'b0;

            memory_busy <= 1'b0;
            wait_count <= 32'd0;
            pending_address <= 64'd0;
            pending_write <= 1'b0;
            pending_error <= 1'b0;
            pending_write_data <= 128'd0;

            ptw_busy <= 1'b0;
            ptw_pending_instruction <= 1'b0;
            ptw_wait_count <= 32'd0;
            ptw_pending_address <= 64'd0;
            ptw_pending_error <= 1'b0;

            timer_enable <= 1'b0;
            timer_irq_enable <= 1'b0;
            timer_periodic <= 1'b0;
            timer_pending <= 1'b0;
            timer_count <= 64'd0;
            timer_compare <= 64'd0;

            uart_enable <= 1'b1;
            uart_rx_irq_enable <= 1'b0;
            uart_divisor <= UART_DIVISOR_RESET;
        end else begin
            if (!ptw_busy && (instruction_ptw_valid || data_ptw_valid)) begin
                ptw_busy <= 1'b1;
                ptw_pending_instruction <= ptw_selected_instruction;
                ptw_pending_address <= ptw_selected_address;
                ptw_pending_error <= !ptw_selected_ram || (ptw_selected_address[2:0] != 3'd0);
                ptw_wait_count <= (MEMORY_WAIT_CYCLES > 0) ? MEMORY_WAIT_CYCLES : 0;
            end else if (ptw_busy) begin
                if (ptw_wait_count != 32'd0) begin
                    ptw_wait_count <= ptw_wait_count - 32'd1;
                end else begin
                    ptw_busy <= 1'b0;
                end
            end

            if (!instruction_memory_busy && icache_memory_valid) begin
                instruction_memory_busy <= 1'b1;
                instruction_wait_count <= (INSTRUCTION_WAIT_CYCLES > 0) ?
                    INSTRUCTION_WAIT_CYCLES : 0;
                pending_instruction_address <= icache_memory_address;
                pending_instruction_error <= (icache_memory_address[63:11] != 53'd0);
            end else if (instruction_memory_busy) begin
                if (instruction_wait_count != 32'd0) begin
                    instruction_wait_count <= instruction_wait_count - 32'd1;
                end else begin
                    instruction_memory_busy <= 1'b0;
                end
            end

            if (timer_enable) begin
                if ((timer_compare != 64'd0) && (timer_count >= (timer_compare - 64'd1))) begin
                    timer_pending <= 1'b1;
                    if (timer_periodic) begin
                        timer_count <= 64'd0;
                    end else begin
                        timer_count <= timer_count + 64'd1;
                        timer_enable <= 1'b0;
                    end
                end else begin
                    timer_count <= timer_count + 64'd1;
                end
            end

            if (!memory_busy && backing_valid) begin
                memory_busy <= 1'b1;
                wait_count <= (MEMORY_WAIT_CYCLES > 0) ? MEMORY_WAIT_CYCLES : 0;
                pending_address <= backing_address;
                pending_write <= backing_write_enable;
                pending_error <= !(backing_ram_region || backing_mmio_valid) ||
                    ((backing_address[11:0] == UART_TX_ADDR) && backing_write_enable && !uart_enable) ||
                    ((backing_address[11:0] == UART_RX_ADDR) && backing_write_enable);
                pending_write_data <= backing_write_data;
            end else if (memory_busy) begin
                if (wait_count != 32'd0) begin
                    wait_count <= wait_count - 32'd1;
                end else if (!(pending_write && (pending_address[11:0] == UART_TX_ADDR) && uart_tx_busy && !pending_error)) begin
                    if (pending_write && !pending_error) begin
                        case (pending_address[11:0])
                            UART_TX_ADDR: begin
                                // TX serializer accepts this byte on the same handshake.
                            end
                            UART_STATUS_ADDR: begin
                                // RX overrun/framing error is write-one-to-clear via the UART.
                            end
                            UART_BAUD_ADDR: begin
                                uart_divisor <= (pending_write_data[31:0] == 32'd0) ? 32'd1 : pending_write_data[31:0];
                            end
                            UART_CONTROL_ADDR: begin
                                uart_enable <= pending_write_data[0];
                                uart_rx_irq_enable <= pending_write_data[1];
                            end
                            TIMER_CONTROL_ADDR: begin
                                timer_enable <= pending_write_data[0];
                                timer_irq_enable <= pending_write_data[1];
                                timer_periodic <= pending_write_data[2];
                            end
                            TIMER_COUNT_ADDR: begin
                                timer_count <= pending_write_data[63:0];
                                timer_pending <= 1'b0;
                            end
                            TIMER_COMPARE_ADDR: begin
                                timer_compare <= pending_write_data[63:0];
                            end
                            TIMER_STATUS_ADDR: begin
                                if (pending_write_data[0]) timer_pending <= 1'b0;
                            end
                            default: begin
                                if (pending_ram_region) begin
                                    data_ram[pending_address[15:4]] <= pending_write_data;
                                end
                            end
                        endcase
                    end
                    memory_busy <= 1'b0;
                end
            end
        end
    end
endmodule
