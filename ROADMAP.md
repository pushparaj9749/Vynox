# Vynox-128 engineering roadmap

This project is being grown as a verifiable processor/SoC, not padded to a target line count. The user's 50k–500k LOC estimate is a scale estimate, not a requirement to add code without engineering purpose. Specifications, reproducible tests, and an honest implementation boundary matter more than raw size.

## Current baseline

- 128-bit integer datapath, 32 integer registers, and a custom 64-bit instruction encoding. The ISA is not RISC-V compatible.
- Five-stage in-order IF/ID/EX/MEM/WB core; EX/MEM and MEM/WB forwarding; one-cycle load-use interlock; control-flow flushes; memory backpressure.
- In-order retirement and precise synchronous exception ordering.
- Initial U/M privilege state and a machine CSR subset, with `MRET`, `ECALL`, `EBREAK`, cycle/retirement counters, machine trap CSRs, and a coarse SoC user-RAM region guard.
- `MTVEC` direct traps and optional instruction-word-strided vectored external interrupts. The one shared timer/UART IRQ is drained into the core before entry; handling is single-level.
- Configurable direct-mapped L1 instruction cache (16 one-word 64-bit lines by default), frontend miss interlock, refill errors, and `FENCE.I` invalidation.
- Integrated four-level instruction/data MMUs, configurable direct-mapped TLBs, a shared SoC PTW arbiter, SATP/SFENCE control, software-built page tables, and an end-to-end warmed-TLB remap test; experimental and not OS-ready.
- 16-line direct-mapped 128-bit L1 data cache; physical-address tags; write-through/write-allocate; RAM-only cacheability; uncached MMIO; serializing `FENCE`.
- SoC read-only program ROM and data RAM, GPIO, timer, full-duplex UART, and one shared timer/UART interrupt line.
- Portable WebAssembly-backed Icarus simulation with directed assembler and RTL regressions.

## Milestones

### 1. Basic architectural traps and privilege subset — implemented

Delivered in this tranche:

- U (`00`) and M (`11`) privilege states, privileged CSR access checks, and serialized CSR write/read-after-write handling.
- `MSTATUS`, `MIE`, `MTVEC`, `MSCRATCH`, `MEPC`, `MCAUSE`, `MTVAL`, read-only `MIP`, `MCYCLE`, and `MINSTRET` subset.
- Precise synchronous illegal-instruction, alignment, instruction/data access, `ECALL`, and `EBREAK` exceptions; trap state records cause, PC, and value before redirecting to `MTVEC`.
- Direct trap vectors and vectored external-interrupt mode; external IRQs use machine-external cause 11 and are maskable through the machine interrupt-enable state.
- `MRET` restores privilege and interrupt-enable state; `IRET` remains compatible with existing programs. Interrupt entry waits for older pipeline work to drain.
- Directed tests cover trap CSRs, user-mode CSR read/write rejection, `ECALL`, return to U mode, CSR ordering, counters, and existing timer/UART interrupt paths.

This is deliberately a small custom-ISA subset, not a complete privileged architecture. U-mode data accesses are now translated when enabled, then checked against the configured physical user-RAM window. The translation/protection model is still incomplete and is not a security boundary. Interrupt sources share one wire, so there is no per-device priority or nested interrupt support. Expand those capabilities only alongside a fuller architecture specification and tests.

### 2. Basic split L1 caches — implemented for the current ROM/RAM map

Delivered in this tranche:

- A direct-mapped I-cache with configurable line count (16 one-instruction lines by default), a one-outstanding refill path, and a core `instruction_ready` frontend stall. Older pipeline stages can continue while a miss is outstanding.
- Stale fetch responses are not consumed after a redirect; refill errors become instruction access faults. `FENCE.I` serializes the frontend and invalidates the cache, including discarding a refill that was already in flight.
- `FENCE` serializes prior in-order memory operations. The D-cache remains write-through/write-allocate and RAM-only; MMIO/unmapped accesses stay uncached. Both cache sizes are configurable at the SoC boundary.
- Directed tests cover cold miss, hit, conflict replacement, invalidation, redirected stale-response suppression, in-flight refill discard, backing error propagation, out-of-range fetches, and SoC wait states.

Boundary: instruction memory is currently a read-only ROM, while data RAM is a separate non-executable address region. Thus there is no writable executable memory or self-modifying-code coherence contract yet. The caches are small, single-word-line/direct-mapped designs without bursts or multiple outstanding refills. These are future extensions, not inferred from the current tests.

### 3. Virtual memory and protection — initial integration implemented; hardening in progress

Delivered in this tranche:

- The core exposes M-mode `SATP` at CSR `0x180`; its custom MODE/root-PPN layout is documented in `VM_SPEC.md`. SATP writes serialize and flush both TLBs. `SFENCE.VMA` (opcode `0x4C`) globally flushes both TLBs; selective VA/ASID invalidation is not implemented.
- Separate instruction/data MMUs translate U-mode requests before I-cache and physical MPU/D-cache access. M mode bypasses translation. Each path has a configurable direct-mapped TLB (four entries by default), a four-level 64-bit-PTE walk, 48-bit canonical-VA checking, software-managed A/D, and 4 KiB leaves only.
- The SoC arbitrates page-table reads through a shared single-outstanding PTW path into data RAM, rejects MMIO/out-of-range PTE addresses, and applies configured wait states. The data-RAM ceiling is 32 KiB by default so full page-table frames fit; the original low MMIO addresses remain fixed.
- Fault metadata traverses IF/ID and ID/EX; translated physical addresses reach the memory path while the original VA is retained for `MTVAL`. Page-fault causes are 12/13/15 and PTW access-fault causes are 1/5/7.
- The coarse U-mode physical-region guard remains after translation and before data bus requests. It still allows only accesses wholly inside `[USER_RAM_BASE, USER_RAM_LIMIT)` (default `[0x000, 0x800)`).
- Directed tests cover standalone MMU behavior plus end-to-end M-mode page-table construction, SATP readback, translated U-mode instruction/data traffic, a warmed mapping update observed only after `SFENCE.VMA`, load/instruction page faults, PTW bus faults, and virtual `MTVAL`.

This is an **experimental initial integration**, not a complete virtual-memory architecture or an OS-ready processor. The ISA is custom; there is no S mode, ASID, selective fence, superpage support, hardware A/D update, unified instruction/data physical memory, executable RAM, or mature page-table software contract. The default instruction ROM is only 2 KiB and M mode always bypasses translation. More adversarial permission/fence/redirect tests, assertions, lint, formal/randomized verification, and synthesis checks remain future work.

### 4. System interconnect and platform

Document the bus protocol and timing; add a scalable interconnect for core, caches/refills, ROM/RAM, and MMIO; verify ordering, errors, reset, and backpressure. Add interrupt routing and per-source priority after the base protocol is stable. Consider DMA only with explicit coherency and security rules.

### 5. Software bring-up

Specify an ABI and calling convention, linker/memory layout, startup/boot path, and exception/interrupt runtime. Add a freestanding compiler/runtime flow and a small test kernel or RTOS with timer scheduling and a UART console. This is a realistic first OS-capable target; Linux compatibility needs a much broader ISA, MMU, privilege model, and device ecosystem.

### 6. Verification and implementation readiness

Add assertions, randomized and differential testing, functional coverage goals, lint, synthesis checks, CDC review, timing constraints, and CI gates. Physical-design readiness additionally requires a selected process/PDK, libraries, clock/reset and DFT strategy, sign-off, and implementation evidence. None of those are implied by passing RTL simulation.

## Explicit current non-goals / not yet delivered

No production-quality virtual memory, page-level security boundary, writable executable RAM, self-modifying-code coherence, nested interrupts, per-device interrupt priority, DMA, SMP, production ABI, OS/RTOS, FPGA timing closure, PDK integration, or commercial silicon qualification is claimed. The initial MMU integration is experimental and remains bounded by the custom ISA and small Harvard-style SoC. These are separate milestones rather than features inferred from code size.
