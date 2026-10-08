IVERILOG ?= iverilog
VVP ?= vvp
RTL := rtl/vynox128_alu.sv rtl/vynox128_core.sv rtl/vynox128_uart.sv rtl/vynox128_dcache.sv rtl/vynox128_icache.sv rtl/vynox128_mpu.sv rtl/vynox128_mmu.sv rtl/vynox128_soc.sv
TB := tb/tb_vynox128_alu.sv tb/tb_vynox128.sv tb/tb_vynox128_soc.sv

.PHONY: test test-asm test-rtl test-wasm test-native assemble-demo clean

test: test-asm test-rtl

test-asm:
	python3 -m unittest discover -s tests -v

# Portable default: Icarus Verilog compiled to WebAssembly.
test-rtl: test-wasm

test-wasm:
	npm run test:rtl

# Optional native flow for machines with Icarus Verilog and VVP installed.
test-native: assemble-demo
	mkdir -p build
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_alu -o build/tb_alu rtl/vynox128_alu.sv tb/tb_vynox128_alu.sv
	$(VVP) build/tb_alu
	$(IVERILOG) -g2012 -Wall -s tb_vynox128 -o build/tb_core rtl/vynox128_alu.sv rtl/vynox128_core.sv tb/tb_vynox128.sv
	$(VVP) build/tb_core
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_isa -o build/tb_isa rtl/vynox128_alu.sv rtl/vynox128_core.sv tb/tb_vynox128_isa.sv
	$(VVP) build/tb_isa
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_faults -o build/tb_faults rtl/vynox128_alu.sv rtl/vynox128_core.sv tb/tb_vynox128_faults.sv
	$(VVP) build/tb_faults
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_trap -o build/tb_trap rtl/vynox128_alu.sv rtl/vynox128_core.sv tb/tb_vynox128_trap.sv
	$(VVP) build/tb_trap
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_pipeline -o build/tb_pipeline rtl/vynox128_alu.sv rtl/vynox128_core.sv tb/tb_vynox128_pipeline.sv
	$(VVP) build/tb_pipeline
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_icache -o build/tb_icache rtl/vynox128_icache.sv tb/tb_vynox128_icache.sv
	$(VVP) build/tb_icache
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_dcache -o build/tb_dcache rtl/vynox128_dcache.sv tb/tb_vynox128_dcache.sv
	$(VVP) build/tb_dcache
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_soc -o build/tb_soc $(RTL) tb/tb_vynox128_soc.sv
	$(VVP) build/tb_soc
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_protection -o build/tb_protection $(RTL) tb/tb_vynox128_protection.sv
	$(VVP) build/tb_protection
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_mmu -o build/tb_mmu rtl/vynox128_mmu.sv tb/tb_vynox128_mmu.sv
	$(VVP) build/tb_mmu
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_vm -o build/tb_vm $(RTL) tb/tb_vynox128_vm.sv
	$(VVP) build/tb_vm
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_irq -o build/tb_irq $(RTL) tb/tb_vynox128_irq.sv
	$(VVP) build/tb_irq
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_uart -o build/tb_uart $(RTL) tb/tb_vynox128_uart.sv
	$(VVP) build/tb_uart
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_uart_rx -o build/tb_uart_rx $(RTL) tb/tb_vynox128_uart_rx.sv
	$(VVP) build/tb_uart_rx
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_uart_irq -o build/tb_uart_irq $(RTL) tb/tb_vynox128_uart_irq.sv
	$(VVP) build/tb_uart_irq
	$(IVERILOG) -g2012 -Wall -s tb_vynox128_uart_unit -o build/tb_uart_unit rtl/vynox128_uart.sv tb/tb_vynox128_uart_unit.sv
	$(VVP) build/tb_uart_unit

assemble-demo:
	mkdir -p build
	python3 tools/vynox128_asm.py programs/sum_1_to_10.s --pad-to 256 -o build/sum_1_to_10.hex
	python3 tools/vynox128_asm.py programs/timer_irq.s --pad-to 256 -o build/timer_irq.hex
	python3 tools/vynox128_asm.py programs/uart_hello.s --pad-to 256 -o build/uart_hello.hex
	python3 tools/vynox128_asm.py programs/uart_rx.s --pad-to 256 -o build/uart_rx.hex
	python3 tools/vynox128_asm.py programs/uart_irq.s --pad-to 256 -o build/uart_irq.hex
	python3 tools/vynox128_asm.py programs/trap_user.s --pad-to 256 -o build/trap_user.hex
	python3 tools/vynox128_asm.py programs/user_protection.s --pad-to 256 -o build/user_protection.hex
	python3 tools/vynox128_asm.py programs/vm_trap_user.s --pad-to 256 -o build/vm_trap_user.hex

clean:
	rm -rf build
