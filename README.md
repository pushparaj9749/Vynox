# Vynox-128

Vynox-128 is a modular **128-bit integer processor RTL project** written in SystemVerilog. This is a hardware-design project—not an Android app and not a fabricated silicon chip. The repository contains the RTL and a simulation testbench; producing physical silicon requires a target process/PDK, synthesis, place-and-route, sign-off, and a foundry tape-out.

## Architecture

- 128-bit integer datapath and 32 general-purpose registers (`R0` is hard-wired to zero)
- 64-bit byte-addressed program counter and data addresses
- Fixed 64-bit instruction words
- Five-stage in-order pipeline (IF/ID/EX/MEM/WB) with EX/MEM and MEM/WB bypassing, one-cycle load-use interlock, branch flushing, and wait-state freezing
- 128-bit ALU with arithmetic, logic, shifts, comparisons, and low-half multiply
- Direct-mapped, configurable 16-line L1 instruction cache (one 64-bit instruction per line) with frontend miss stalls and refill-error propagation
- Direct-mapped 16-line (256-byte), 128-bit L1 data cache (write-through/write-allocate); MMIO and unmapped addresses bypass it
- Integrated `vynox128_soc` with a 2 KiB program ROM, independently wait-stated instruction refills, wait-state RAM, both L1 caches, GPIO, timer, and full-duplex UART
- 128-bit GPIO input/output registers and 8N1 full-duplex UART
- U/M privilege states, machine CSRs, precise traps, and a machine-only `SATP` CSR with a custom Bare/Sv48-style mode/root-PPN layout
- Separate direct-mapped instruction/data TLBs and four-level 4 KiB page walks for U mode; M mode bypasses translation
- Translation faults preserve original virtual addresses in `MTVAL`; translated physical addresses feed the I-cache, MPU, D-cache, and MMIO map
- A coarse physical-region guard still confines U-mode data accesses to a configurable RAM window (default `[0x000, 0x800)`), after translation and before the bus
- Precise synchronous traps through `MTVEC`; timer/UART share one machine-external interrupt input, with drain-before-entry and single-level (non-nested) handling
- `MRET` restores privilege and interrupt-enable state; `IRET` remains a compatibility alias; legacy `EI`/`DI` remain supported
- Aligned 16-byte data transfers; load/store addresses must be 16-byte aligned
- Illegal instructions, alignment/access faults, `ECALL`, and `EBREAK` record a trap and redirect to the machine trap vector; `fault`/`fault_code` are sticky debug outputs, not a claim that the core halted
- Two-pass Python assembler with labels, CSR names, trap instructions, and a small `LI` pseudo-instruction

The data interface uses `data_valid`/`data_ready`; `data_error` reports a failed accepted access. A transfer is accepted on a rising clock edge when both are high. The instruction interface adds `instruction_ready`: the frontend advances its PC only when a valid instruction is accepted and otherwise stalls while older pipeline stages drain. Bare-core test harnesses without an instruction cache tie `instruction_ready` high. The SoC's I-cache serves a 256-word (2 KiB) read-only program ROM; out-of-range physical fetches become precise instruction access faults. The default data-RAM address ceiling is `RAM_BYTES=0x8000` (configurable from 4–64 KiB in 4 KiB steps). The fixed MMIO window overlays `0xF70`–`0xFFF`; the remainder is RAM. The page-table walker reads 64-bit PTEs directly from this RAM and rejects MMIO/out-of-range PTE addresses:

| Byte address | Register | Behavior |
|---:|---|---|
| `0x000`–`0xF6F`, `0x1000`–`RAM_BYTES-1` | Data RAM | 128-bit words; default address ceiling `0x8000` (MMIO hole excluded) |
| `0xF70` | UART RX data | Read the received byte; read consumes it |
| `0xF80` | UART TX data | Store a byte to send; writes wait until TX is ready |
| `0xF90` | UART status | bit 0 TX busy, bit 1 TX ready, bit 2 enabled, bit 3 RX valid, bit 4 RX error |
| `0xFA0` | UART divisor | Clock cycles per serial bit; minimum 1 |
| `0xFB0` | UART control | bit 0 enables UART, bit 1 enables RX interrupt |
| `0xFC0` | Timer control | bit 0 enable, bit 1 IRQ enable, bit 2 periodic mode |
| `0xFD0` | Timer count | 64-bit counter, readable/writable |
| `0xFE0` | Timer compare | 64-bit threshold; zero disables compare |
| `0xFF0` | Timer status | bit 0 pending; write 1 to clear |

The SoC's static physical-region guard allows U-mode 16-byte data accesses only when the entire translated physical transfer fits in `[USER_RAM_BASE, USER_RAM_LIMIT)` (default `[0x000, 0x800)`); the upper RAM window and MMIO are machine-only. Denied loads/stores trap precisely with access-fault cause 5/7 and the original virtual address in `MTVAL`, before any bus side effect. This remains a coarse physical-region rule layered after translation, not a complete security boundary.

The L1 instruction cache is direct-mapped, defaults to 16 one-instruction lines, and has a configurable line count. A miss stalls only instruction fetch while older pipeline work proceeds; a redirected fetch never consumes a stale refill. `FENCE` serializes the in-order memory stream and `FENCE.I` serializes the frontend and invalidates the I-cache, including discarding an in-flight refill. The SoC makes only the ROM range instruction-cacheable and only data RAM data-cacheable. The L1 data cache is direct-mapped with 16 one-word lines, write-through/write-allocate policy, and physical-address tags. Successful stores update the cache and backing RAM; cache misses wait for the backing-memory handshake. UART/timer MMIO and unmapped addresses bypass D-cache. Executable RAM and self-modifying-code coherence are not supported yet. The UART receives and transmits 8N1 frames, least-significant bit first; TX writes wait for space, RX has a one-byte holding register, and status bit 4 is write-one-to-clear for overrun/framing errors. Its reset divisor is the SoC `UART_DIVISOR` parameter. The timer is clocked by the SoC clock. Its one-shot mode disables itself on compare; periodic mode reloads the counter. Timer and UART RX currently share one external IRQ input. The reset trap vector is direct at byte address `0x100`; machine software may configure `MTVEC` for a direct vector or an instruction-word-strided vectored interrupt table. Interrupt nesting and per-device prioritization are not supported.

## Virtual memory (initial integration)

U-mode instruction and data addresses are translated independently before cache/bus lookup when `SATP.MODE=9`. Each path has a configurable direct-mapped TLB (four entries by default) and a four-level, 512-entry-per-level page-table walker with 64-bit PTEs and 4 KiB leaves. Virtual addresses must be 48-bit canonical; superpages and ASIDs are not implemented. PTE permissions are U/R/W/X plus software-managed A/D; the walker never modifies page tables.

This is a custom Sv48-inspired format, not RISC-V-compatible SATP semantics. CSR `SATP` (`0x180`) uses bits `[63:60]` for mode (`0` Bare, `9` translated), `[59:40]` reserved, and `[39:0]` for the root PPN. Unsupported modes are coerced to Bare. M mode always bypasses translation. `SFENCE.VMA` (opcode `0x4C`, M-mode only) globally flushes both TLBs; its optional register operands are accepted by the assembler but not used for selective invalidation. SATP writes are serialized and also request a global flush.

The SoC arbitrates instruction/data page-table reads through a shared single-outstanding PTW path into physical data RAM; I-walks have priority if both request together. PTW latency follows `MEMORY_WAIT_CYCLES`. Page faults report causes 12/13/15, page-table bus failures report access-fault causes 1/5/7, and `MTVAL` carries the original VA. The I-cache remains backed by the 2 KiB ROM while the D-cache/MPU/MMIO path uses physical data addresses. The end-to-end VM program builds page tables in M mode using ordinary `STORE`s, then modifies a warmed data mapping and verifies that `SFENCE.VMA` makes the next U-mode access use the new physical page; it also exercises SATP readback, page faults, PTW bus faults, and virtual `MTVAL`.

This is an initial experimental integration—not an OS-capable MMU or complete isolation boundary. There is no S mode, ASID, selective fence, superpage, hardware A/D update, executable RAM, or unified instruction/data physical memory. See [`VM_SPEC.md`](VM_SPEC.md) for the exact draft contract and its limits.

## Instruction encoding

Each instruction is 64 bits:

```text
63        56 55    51 50    46 45    41 40                         9 8       0
+-----------+--------+--------+--------+-----------------------------+---------+
|  opcode   |   rd   |  rs1   |  rs2   | signed immediate (32 bits) | reserved|
+-----------+--------+--------+--------+-----------------------------+---------+
```

- R-type instructions use `rd`, `rs1`, and `rs2`.
- Immediate and load instructions use `rd`, `rs1`, and the sign-extended immediate.
- `STORE` uses `rs1` as the base, `rs2` as the 128-bit value, and the immediate as a byte offset.
- Branch offsets are signed counts of 64-bit instructions: target = `PC + (immediate * 8)`.
- `JAL` saves `PC + 8` in `rd` and jumps by the same relative-offset rule.

| Opcode | Mnemonic | Behavior |
|---:|---|---|
| `00` | `NOP` | No operation |
| `01` | `ADD` | `rd = rs1 + rs2` |
| `02` | `SUB` | `rd = rs1 - rs2` |
| `03`–`05` | `AND`, `OR`, `XOR` | Bitwise register operation |
| `06`–`08` | `SLL`, `SRL`, `SRA` | 128-bit shifts; shift count is `rs2[6:0]` |
| `09`–`0A` | `SLT`, `SLTU` | Signed/unsigned less-than result |
| `0B`–`0E` | `ADDI`, `ANDI`, `ORI`, `XORI` | Register/immediate operation |
| `0F` | `MUL` | Low 128 bits of the 128 × 128-bit product |
| `10` | `LOAD` | Load one 128-bit word from memory |
| `11` | `STORE` | Store one 128-bit word to memory |
| `20`–`25` | `BEQ`, `BNE`, `BLT`, `BGE`, `BLTU`, `BGEU` | Conditional relative branch |
| `30` | `JAL` | Relative jump and link |
| `40` | `IN` | Read the 128-bit GPIO input |
| `41` | `OUT` | Write a register to the 128-bit GPIO output |
| `42` | `EI` | Enable maskable interrupts |
| `43` | `DI` | Disable maskable interrupts |
| `44` | `IRET` | Compatibility alias for machine trap return |
| `45` | `CSRR` | Read a machine CSR into `rd` (CSR address is in the immediate field) |
| `46` | `CSRW` | Write `rs1[63:0]` to a machine CSR (CSR address is in the immediate field) |
| `47` | `MRET` | Return from a machine trap using `MEPC` and `MSTATUS` |
| `48` | `ECALL` | Raise a privilege-specific environment-call exception |
| `49` | `EBREAK` | Raise a breakpoint exception |
| `4A` | `FENCE` | Serialize prior memory operations in the in-order system |
| `4B` | `FENCE.I` | Serialize fetch and invalidate the instruction cache |
| `4C` | `SFENCE.VMA` | Serialize and globally flush instruction/data TLBs (machine mode only) |
| `FF` | `HALT` | Stop execution (machine mode only) |

Fault codes are stable debug outputs: `00` none, `01` illegal instruction/privilege violation, `02` unaligned memory access, `03` data-bus or page-walk access error, `04` instruction access/fetch fault, `05` environment call, `06` breakpoint, `07` physical-region protection violation, `08` page fault, and `FF` internal controller fault. A synchronous exception updates the machine trap CSRs and redirects execution; observing `fault` does not by itself mean the core has halted.

## Machine trap and CSR subset

This is a small custom machine/user privilege subset, not a claim of RISC-V compliance. It provides U (`00`) and M (`11`) modes, precise synchronous traps, an external machine interrupt, and serialized CSR writes. CSR instructions are privileged to M mode; unsupported or read-only writes trap as illegal instructions. U-mode requests now undergo page translation when enabled, then the physical user-RAM guard blocks accesses outside its configured window. The design still lacks a full protection/virtual-memory architecture and must not be treated as a production security boundary.

| CSR | Address | Current behavior |
|---|---:|---|
| `MSTATUS` | `0x300` | Implements MIE bit 3, MPIE bit 7, and MPP bits 12:11 (U/M only) |
| `MIE` | `0x304` | Machine external interrupt enable in bit 11 |
| `MTVEC` | `0x305` | Direct mode (`mode=0`) or vectored external-interrupt mode (`mode=1`); the instruction target must be 8-byte aligned |
| `MSCRATCH` | `0x340` | Machine software scratch register |
| `MEPC` | `0x341` | Trap return PC; low three bits are cleared |
| `MCAUSE` | `0x342` | Synchronous exception code or interrupt flag plus cause |
| `MTVAL` | `0x343` | Faulting instruction/address/PC where applicable; zero for `ECALL` |
| `MIP` | `0x344` | Read-only external IRQ pending indication in bit 11 |
| `SATP` | `0x180` | Custom MODE `[63:60]` (0 Bare, 9 Sv48-style), reserved `[59:40]`, root PPN `[39:0]`; unsupported modes become Bare |
| `MCYCLE` | `0xC00` | Read-only cycle counter |
| `MINSTRET` | `0xC02` | Read-only retired-instruction counter |

Synchronous cause values currently used are instruction access/page fault 1/12, illegal instruction 2, breakpoint 3, load alignment/access/page fault 4/5/13, store alignment/access/page fault 6/7/15, U-mode `ECALL` 8, and M-mode `ECALL` 11. The shared timer/UART input is machine external interrupt cause 11 (with the interrupt flag set in `MCAUSE`). In vectored `MTVEC` mode, an interrupt target is `base + cause * 8` because instructions are 8 bytes; synchronous traps always use the base. `MRET` restores privilege and MIE from MPP/MPIE, then clears MPP to U. Traps are single-level; `MRET`/`IRET` do not support nested contexts.

## Assembler

The two-pass assembler accepts `R0`–`R31` (or `X0`–`X31`), decimal/hex immediates, comments, and labels. Branch and jump labels are assembled as signed PC-relative instruction offsets. Example syntax:

```asm
LI    R1, 10
loop:
ADDI  R1, R1, -1
BNE   R1, R0, loop
LOAD  R2, R0, 0x100
STORE R2, R0, 0x110
HALT
```

`LOAD`/`STORE` offsets must be 16-byte aligned; the processor also checks the final effective address at runtime. `LI rd, imm` is a one-instruction pseudo-op for `ADDI rd, R0, imm`. `FENCE` and `FENCE.I` take no operands. `SFENCE.VMA` accepts no operands or two registers (currently ignored; invalidation is global). `.org byte_address` inserts NOPs up to an aligned address; the vectored timer demo places its machine-external interrupt handler at `.org 0x158` (`0x100 + 11 * 8`).

## Assemble and simulate

Assemble the example program to a 64-bit-word hex file:

```sh
python3 tools/vynox128_asm.py programs/sum_1_to_10.s --pad-to 256 -o build/sum_1_to_10.hex
```

The default test uses Icarus Verilog compiled to WebAssembly, so it does not need a system-wide simulator install. Install the project dependency once, then run:

```sh
npm install
make test
```

`make test` runs assembler unit tests plus seventeen RTL simulations: ALU edge cases, the five-stage core/ISA, fault handling, machine/user privilege and CSR traps, pipeline bypass/interlock/flush behavior, I-cache miss/hit/conflict/invalidation/redirect/refill-error behavior, D-cache miss/hit/write-through/MMIO behavior, standalone four-level MMU tests, the SoC demo, an integrated virtual-memory SoC test, U-mode physical-region protection, vectored timer interrupts, UART TX serialization, UART RX polling/decoding, UART RX interrupt handling, and UART receive-error cases. The trap program checks CSR read-after-write, cycle/retirement counters, user-mode CSR read/write rejection, `ECALL`, trap-state CSRs, and `MRET`; the SoC demo executes `FENCE` and `FENCE.I`, while the protection test checks allowed user RAM and denied kernel-RAM loads/stores with no bus side effect. The UART suite checks overrun retention, framing-error detection/clear, RX IRQs, and read-to-clear; other tests exercise memory wait states and a load-use bubble. On a machine with native Icarus Verilog and VVP installed, use `make test-native` instead.

## Scope status

The five-stage pipeline, split L1 caches, initial machine/user traps, coarse physical data guard, and an experimental four-level virtual-memory path are integrated and directed-simulation tested. The VM slice includes SATP/SFENCE.VMA control, separate I/D TLBs, shared PTW arbitration, canonical-address and permission checks, page/access-fault routing, and original-VA MTVAL reporting. The implementation remains deliberately limited: 4 KiB leaves only, no ASID or S mode, M-mode bypass, 2 KiB read-only instruction ROM, no executable RAM, and a coarse physical user window after translation. The trap architecture still has one external interrupt input and no nesting or device priority. This is **not yet an OS-capable or silicon-ready CPU** and needs broader formal/randomized verification, synthesis, and implementation work. Passing RTL simulations are not synthesis, timing-closure, FPGA, or silicon evidence. See [`VM_SPEC.md`](VM_SPEC.md) and [`ROADMAP.md`](ROADMAP.md) for the exact current contract and boundaries.
