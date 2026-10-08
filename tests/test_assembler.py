import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from vynox128_asm import AssemblyError, assemble_text  # noqa: E402


class AssemblerTests(unittest.TestCase):
    def test_register_immediate_encoding_and_li_pseudo(self):
        words = assemble_text("LI R1, 5\nADDI R2, R1, -1\n")
        self.assertEqual(len(words), 2)
        self.assertEqual(words[0] >> 56, 0x0B)
        self.assertEqual((words[0] >> 51) & 0x1F, 1)
        self.assertEqual((words[0] >> 46) & 0x1F, 0)
        self.assertEqual((words[0] >> 9) & 0xFFFF_FFFF, 5)
        self.assertEqual((words[1] >> 9) & 0xFFFF_FFFF, 0xFFFF_FFFF)

    def test_label_branch_is_relative_to_current_instruction(self):
        words = assemble_text("""
            LI R1, 1
        loop:
            ADDI R1, R1, -1
            BNE R1, R0, loop
            HALT
        """)
        self.assertEqual((words[2] >> 56) & 0xFF, 0x21)
        self.assertEqual((words[2] >> 9) & 0xFFFF_FFFF, 0xFFFF_FFFF)

    def test_store_register_fields(self):
        word = assemble_text("STORE R3, R4, 0x100\n")[0]
        self.assertEqual((word >> 51) & 0x1F, 0)  # rd is unused by STORE
        self.assertEqual((word >> 46) & 0x1F, 4)  # base
        self.assertEqual((word >> 41) & 0x1F, 3)  # value register
        self.assertEqual((word >> 9) & 0xFFFF_FFFF, 0x100)

    def test_org_places_interrupt_handler_and_resolves_label(self):
        words = assemble_text("JAL R0, handler\n.org 0x100\nhandler: IRET\n")
        self.assertEqual(len(words), 33)
        self.assertEqual((words[0] >> 9) & 0xFFFF_FFFF, 32)
        self.assertEqual((words[32] >> 56) & 0xFF, 0x44)
        self.assertTrue(all(word == 0 for word in words[1:32]))

    def test_interrupt_control_instructions(self):
        words = assemble_text("EI\nDI\nIRET\nMRET\nECALL\nEBREAK\nFENCE\nFENCE.I\n")
        self.assertEqual([(word >> 56) & 0xFF for word in words],
                         [0x42, 0x43, 0x44, 0x47, 0x48, 0x49, 0x4A, 0x4B])

    def test_satp_and_sfence_vma_assembly(self):
        words = assemble_text("CSRW R3, SATP\nSFENCE.VMA\nSFENCE.VMA R4, R5\n")
        self.assertEqual((words[0] >> 9) & 0xFFF, 0x180)
        self.assertEqual((words[1] >> 56) & 0xFF, 0x4C)
        self.assertEqual((words[2] >> 56) & 0xFF, 0x4C)
        self.assertEqual((words[2] >> 46) & 0x1F, 4)
        self.assertEqual((words[2] >> 41) & 0x1F, 5)
        with self.assertRaises(AssemblyError):
            assemble_text("SFENCE.VMA R1\n")

    def test_machine_csr_assembly_and_address_validation(self):
        read_csr, write_csr = assemble_text("CSRR R1, MSTATUS\nCSRW R2, 0xC00\n")
        self.assertEqual((read_csr >> 56) & 0xFF, 0x45)
        self.assertEqual((read_csr >> 51) & 0x1F, 1)
        self.assertEqual((read_csr >> 9) & 0xFFF, 0x300)
        self.assertEqual((write_csr >> 56) & 0xFF, 0x46)
        self.assertEqual((write_csr >> 46) & 0x1F, 2)
        self.assertEqual((write_csr >> 9) & 0xFFF, 0xC00)
        with self.assertRaises(AssemblyError):
            assemble_text("CSRR R1, 0x1000\n")

    def test_rejects_bad_registers_and_duplicate_labels(self):
        with self.assertRaises(AssemblyError):
            assemble_text("LI R32, 1\n")
        with self.assertRaises(AssemblyError):
            assemble_text("again: NOP\nAGAIN: HALT\n")

    def test_rejects_misaligned_memory_offset_and_out_of_range_immediate(self):
        with self.assertRaises(AssemblyError):
            assemble_text("LI R1, 4294967296\n")
        with self.assertRaises(AssemblyError):
            assemble_text("LOAD R1, R0, 3\n")


if __name__ == "__main__":
    unittest.main()
