#!/usr/bin/env python3
"""Small two-pass assembler for the Vynox-128 custom ISA."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

OPCODES = {
    "NOP": 0x00,
    "ADD": 0x01,
    "SUB": 0x02,
    "AND": 0x03,
    "OR": 0x04,
    "XOR": 0x05,
    "SLL": 0x06,
    "SRL": 0x07,
    "SRA": 0x08,
    "SLT": 0x09,
    "SLTU": 0x0A,
    "ADDI": 0x0B,
    "ANDI": 0x0C,
    "ORI": 0x0D,
    "XORI": 0x0E,
    "MUL": 0x0F,
    "LOAD": 0x10,
    "STORE": 0x11,
    "BEQ": 0x20,
    "BNE": 0x21,
    "BLT": 0x22,
    "BGE": 0x23,
    "BLTU": 0x24,
    "BGEU": 0x25,
    "JAL": 0x30,
    "IN": 0x40,
    "OUT": 0x41,
    "EI": 0x42,
    "DI": 0x43,
    "IRET": 0x44,  # compatibility alias for machine return
    "CSRR": 0x45,
    "CSRW": 0x46,
    "MRET": 0x47,
    "ECALL": 0x48,
    "EBREAK": 0x49,
    "FENCE": 0x4A,
    "FENCE.I": 0x4B,
    "SFENCE.VMA": 0x4C,
    "HALT": 0xFF,
}

CSR_ADDRESSES = {
    "MSTATUS": 0x300,
    "MIE": 0x304,
    "MTVEC": 0x305,
    "MSCRATCH": 0x340,
    "MEPC": 0x341,
    "MCAUSE": 0x342,
    "MTVAL": 0x343,
    "MIP": 0x344,
    "MCYCLE": 0xC00,
    "MINSTRET": 0xC02,
    "SATP": 0x180,
}

R_TYPE = {"ADD", "SUB", "AND", "OR", "XOR", "SLL", "SRL", "SRA", "SLT", "SLTU", "MUL"}
I_TYPE = {"ADDI", "ANDI", "ORI", "XORI"}
BRANCHES = {"BEQ", "BNE", "BLT", "BGE", "BLTU", "BGEU"}
LABEL_RE = re.compile(r"^[A-Za-z_.$][A-Za-z0-9_.$]*$")
REGISTER_RE = re.compile(r"^[RrXx](\d+)$")


class AssemblyError(ValueError):
    """An assembly source or operand is invalid."""


def _error(line_number: int, message: str) -> AssemblyError:
    return AssemblyError(f"line {line_number}: {message}")


def _strip_comment(line: str) -> str:
    return re.split(r"[;#]", line, maxsplit=1)[0].strip()


def _register(token: str, line_number: int) -> int:
    match = REGISTER_RE.fullmatch(token.strip())
    if not match:
        raise _error(line_number, f"expected a register R0-R31, got {token!r}")
    number = int(match.group(1))
    if not 0 <= number <= 31:
        raise _error(line_number, f"register index out of range: {token!r}")
    return number


def _integer(token: str, line_number: int) -> int:
    try:
        return int(token, 0)
    except ValueError as exc:
        raise _error(line_number, f"expected an integer, got {token!r}") from exc


def _immediate32(value: int, line_number: int) -> int:
    if not -(1 << 31) <= value <= 0xFFFF_FFFF:
        raise _error(line_number, f"immediate does not fit in 32 bits: {value}")
    return value & 0xFFFF_FFFF


def _csr(token: str, line_number: int) -> int:
    name = token.strip().upper()
    if name in CSR_ADDRESSES:
        return CSR_ADDRESSES[name]
    value = _integer(token, line_number)
    if not 0 <= value <= 0xFFF:
        raise _error(line_number, f"CSR address must fit in 12 bits: {token!r}")
    return value


def _encode(opcode: int, rd: int = 0, rs1: int = 0, rs2: int = 0, imm: int = 0) -> int:
    return (
        ((opcode & 0xFF) << 56)
        | ((rd & 0x1F) << 51)
        | ((rs1 & 0x1F) << 46)
        | ((rs2 & 0x1F) << 41)
        | ((imm & 0xFFFF_FFFF) << 9)
    )


def _target_offset(token: str, pc: int, labels: dict[str, int], line_number: int) -> int:
    label_address = labels.get(token.lower())
    if label_address is not None:
        delta = label_address - pc
        if delta % 8:
            raise _error(line_number, f"branch target {token!r} is not instruction-aligned")
        return _immediate32(delta // 8, line_number)
    # A numeric branch target is an already-relative count of instructions.
    return _immediate32(_integer(token, line_number), line_number)


def assemble_text(source: str) -> list[int]:
    """Assemble source text into 64-bit instruction words."""
    labels: dict[str, int] = {}
    instructions: list[tuple[int, int, str]] = []
    pc = 0

    # First pass: collect labels and instruction addresses.
    for line_number, original_line in enumerate(source.splitlines(), start=1):
        text = _strip_comment(original_line)
        if not text:
            continue

        if ":" in text:
            label, text = text.split(":", maxsplit=1)
            label = label.strip()
            if not LABEL_RE.fullmatch(label):
                raise _error(line_number, f"invalid label {label!r}")
            key = label.lower()
            if key in labels:
                raise _error(line_number, f"duplicate label {label!r}")
            labels[key] = pc
            text = text.strip()
            if not text:
                continue

        fields = [field for field in re.split(r"[\s,]+", text.strip()) if field]
        if fields and fields[0].upper() == ".ORG":
            if len(fields) != 2:
                raise _error(line_number, ".org expects one byte address")
            target_pc = _integer(fields[1], line_number)
            if target_pc < pc:
                raise _error(line_number, ".org cannot move backwards")
            if target_pc % 8:
                raise _error(line_number, ".org address must be 8-byte aligned")
            while pc < target_pc:
                instructions.append((pc, line_number, "NOP"))
                pc += 8
            continue

        instructions.append((pc, line_number, text))
        pc += 8

    # Second pass: encode instructions and resolve relative branches.
    words: list[int] = []
    for instruction_pc, line_number, text in instructions:
        fields = [field for field in re.split(r"[\s,]+", text.strip()) if field]
        mnemonic = fields[0].upper()
        operands = fields[1:]
        if mnemonic not in OPCODES and mnemonic != "LI":
            raise _error(line_number, f"unknown instruction {mnemonic!r}")

        if mnemonic in {"NOP", "HALT", "EI", "DI", "IRET", "MRET", "ECALL", "EBREAK", "FENCE", "FENCE.I"}:
            if operands:
                raise _error(line_number, f"{mnemonic} takes no operands")
            words.append(_encode(OPCODES[mnemonic]))
            continue

        if mnemonic == "SFENCE.VMA":
            if len(operands) not in {0, 2}:
                raise _error(line_number, "SFENCE.VMA expects no operands or virtual_address_register, asid_register")
            if operands:
                source_a = _register(operands[0], line_number)
                source_b = _register(operands[1], line_number)
            else:
                source_a = source_b = 0
            words.append(_encode(OPCODES[mnemonic], rs1=source_a, rs2=source_b))
            continue

        if mnemonic == "CSRR":
            if len(operands) != 2:
                raise _error(line_number, "CSRR expects destination_register, csr")
            rd = _register(operands[0], line_number)
            csr = _csr(operands[1], line_number)
            words.append(_encode(OPCODES[mnemonic], rd=rd, imm=csr))
            continue

        if mnemonic == "CSRW":
            if len(operands) != 2:
                raise _error(line_number, "CSRW expects source_register, csr")
            rs1 = _register(operands[0], line_number)
            csr = _csr(operands[1], line_number)
            words.append(_encode(OPCODES[mnemonic], rs1=rs1, imm=csr))
            continue

        if mnemonic in R_TYPE:
            if len(operands) != 3:
                raise _error(line_number, f"{mnemonic} expects rd, rs1, rs2")
            rd, rs1, rs2 = (_register(item, line_number) for item in operands)
            words.append(_encode(OPCODES[mnemonic], rd, rs1, rs2))
            continue

        if mnemonic in I_TYPE:
            if len(operands) != 3:
                raise _error(line_number, f"{mnemonic} expects rd, rs1, immediate")
            rd = _register(operands[0], line_number)
            rs1 = _register(operands[1], line_number)
            imm = _immediate32(_integer(operands[2], line_number), line_number)
            words.append(_encode(OPCODES[mnemonic], rd, rs1, imm=imm))
            continue

        if mnemonic == "LI":
            if len(operands) != 2:
                raise _error(line_number, "LI expects rd, immediate")
            rd = _register(operands[0], line_number)
            imm = _immediate32(_integer(operands[1], line_number), line_number)
            words.append(_encode(OPCODES["ADDI"], rd, 0, imm=imm))
            continue

        if mnemonic == "LOAD":
            if len(operands) != 3:
                raise _error(line_number, "LOAD expects rd, base_register, byte_offset")
            rd, base = _register(operands[0], line_number), _register(operands[1], line_number)
            offset = _integer(operands[2], line_number)
            if offset % 16:
                raise _error(line_number, "LOAD byte offset must be 16-byte aligned")
            imm = _immediate32(offset, line_number)
            words.append(_encode(OPCODES[mnemonic], rd, base, imm=imm))
            continue

        if mnemonic == "STORE":
            if len(operands) != 3:
                raise _error(line_number, "STORE expects source_register, base_register, byte_offset")
            source, base = _register(operands[0], line_number), _register(operands[1], line_number)
            offset = _integer(operands[2], line_number)
            if offset % 16:
                raise _error(line_number, "STORE byte offset must be 16-byte aligned")
            imm = _immediate32(offset, line_number)
            words.append(_encode(OPCODES[mnemonic], rs1=base, rs2=source, imm=imm))
            continue

        if mnemonic in BRANCHES:
            if len(operands) != 3:
                raise _error(line_number, f"{mnemonic} expects rs1, rs2, label_or_relative_offset")
            rs1, rs2 = _register(operands[0], line_number), _register(operands[1], line_number)
            offset = _target_offset(operands[2], instruction_pc, labels, line_number)
            words.append(_encode(OPCODES[mnemonic], rs1=rs1, rs2=rs2, imm=offset))
            continue

        if mnemonic == "JAL":
            if len(operands) != 2:
                raise _error(line_number, "JAL expects rd, label_or_relative_offset")
            rd = _register(operands[0], line_number)
            offset = _target_offset(operands[1], instruction_pc, labels, line_number)
            words.append(_encode(OPCODES[mnemonic], rd=rd, imm=offset))
            continue

        if mnemonic in {"IN", "OUT"}:
            if len(operands) != 1:
                raise _error(line_number, f"{mnemonic} expects one register")
            rd = _register(operands[0], line_number)
            words.append(_encode(OPCODES[mnemonic], rd=rd))
            continue

        raise _error(line_number, f"assembler does not implement {mnemonic}")

    return words


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="assembly source file")
    parser.add_argument("-o", "--output", type=Path, help="output hex file (default: stdout)")
    parser.add_argument("--pad-to", type=int, default=0, help="pad with NOP words up to this word count")
    args = parser.parse_args(argv)

    try:
        words = assemble_text(args.source.read_text(encoding="utf-8"))
        if args.pad_to < 0:
            raise AssemblyError("--pad-to must be non-negative")
        if args.pad_to and len(words) > args.pad_to:
            raise AssemblyError(f"program has {len(words)} words, more than --pad-to {args.pad_to}")
        if args.pad_to:
            words.extend([0] * (args.pad_to - len(words)))
    except (OSError, AssemblyError) as exc:
        print(f"assembler: {exc}", file=sys.stderr)
        return 1

    output = "".join(f"{word:016x}\n" for word in words)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="ascii")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
