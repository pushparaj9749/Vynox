# Vynox-128 virtual-memory contract (initial integration)

This document describes the first core/SoC-connected virtual-memory slice. Vynox-128 remains a custom ISA and this Sv48-inspired design is **not RISC-V-compatible**. Passing directed RTL tests does not establish OS readiness, complete isolation, or silicon quality.

## Address model

- Virtual addresses are 64-bit values. Translated U-mode addresses must be canonical: `VA[63:48]` equals sixteen copies of `VA[47]`.
- The MMU forms 52-bit physical addresses (`PA[51:0]`); the current SoC implements a much smaller physical data-RAM window and a 2 KiB instruction ROM.
- Pages are 4 KiB. A four-level radix tree has 512 64-bit entries per table, indexed by `VA[47:39]`, `[38:30]`, `[29:21]`, and `[20:12]`; `VA[11:0]` is the page offset.
- Only 4 KiB leaves are supported. Superpages are rejected.

## Translation control

`SATP` is an M-mode-only CSR at `0x180` with a custom layout:

| Bits | Meaning |
|---:|---|
| 63:60 | MODE: `0` = Bare, `9` = four-level translation |
| 59:40 | Reserved; reads as zero after a write |
| 39:0 | Root page-table PPN (40 bits) |

Writes with MODE `9` retain the low 40-bit root PPN and clear the reserved field. Any other mode is coerced to Bare (`SATP=0`). An SATP write is serialized and requests a global I-TLB/D-TLB flush. Machine mode always bypasses translation, even when SATP is active; U mode uses translation only when MODE is `9`.

`SFENCE.VMA` is the custom opcode `0x4C`, legal only in M mode. It serializes the pipeline and globally invalidates both TLBs. The assembler accepts either no operands or two registers, but the current implementation ignores both operands: there is no ASID or selective VA/ASID invalidation.

## PTE format

| Bits | Meaning |
|---:|---|
| 0 | V: valid |
| 1 | R: readable |
| 2 | W: writable |
| 3 | X: executable |
| 4 | U: user accessible |
| 5 | A: accessed (software must set it) |
| 6 | D: dirty (software must set it for writes) |
| 11:7 | Reserved; must be zero |
| 51:12 | Physical page number (40 bits) |
| 63:52 | Reserved; must be zero |

`W=1,R=0` is invalid. A valid entry with no `R/W/X` bits is a non-leaf pointer; its other permission bits must be zero. A leaf above level 0 is rejected. A U-mode leaf access requires `U=1`, `A=1`, and the requested `R`, `W+D`, or `X` permission. Hardware does not set A/D bits.

## TLBs, walkers, and fault behavior

- The SoC instantiates separate instruction and data MMUs. Each has a configurable, direct-mapped TLB (four entries by default), tagged by VPN plus root PPN; ASIDs are not implemented.
- Translation occurs before I-cache lookup and before the physical MPU/D-cache/MMIO path. The I-cache only serves physical addresses in the 2 KiB ROM; the data path uses the separate RAM/MMIO map. This is a Harvard-style platform, not unified executable memory.
- The instruction and data walkers share a single-outstanding SoC PTW arbiter. If both request simultaneously, instruction walks have priority. PTW reads are 64-bit and use the SoC physical data-RAM array with `MEMORY_WAIT_CYCLES` latency. MMIO and out-of-range PTE addresses are rejected as access faults.
- The default data-RAM address ceiling is `RAM_BYTES=0x8000` (32 KiB; configurable in 4 KiB steps up to 64 KiB). The fixed `0xF70`–`0xFFF` MMIO window overlays the low 4 KiB; PTW cannot read from that window.
- Page-fault causes are instruction/load/store = `12/13/15`. Page-table bus failures become instruction/load/store access faults = `1/5/7`. `MTVAL` carries the original virtual address for translation faults and translated data bus/protection faults. Physical addresses, not virtual addresses, reach the cache/bus.
- After successful U-mode translation, the existing physical-region MPU still requires the entire 16-byte data transfer to fit in `[USER_RAM_BASE, USER_RAM_LIMIT)` (default `[0x000, 0x800)`). This is intentionally a second, coarse platform policy—not a replacement for PTE checks.

## Validation and limits

- `tb/tb_vynox128_mmu.sv` tests standalone walks, TLB hits/flush, permissions, invalid/canonical addresses, and page/access faults.
- `tb/tb_vynox128_vm.sv` tests integrated SATP write/readback, SFENCE, translated U-mode instruction fetch and data store/load, load and instruction page faults, an out-of-RAM PTW fault, and virtual `MTVAL`.
- The feature is still an experimental integration. There is no S mode, ASID, selective TLB invalidation, superpage support, hardware A/D update, executable RAM, unified instruction/data memory, mature OS ABI, or complete protection architecture. M-mode bypass and the fixed ROM also constrain software use. Do not describe this as a production or OS-ready MMU.
