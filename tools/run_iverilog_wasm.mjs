import { execFileSync } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { simulate } from '@veriflow/iverilog-wasm';

await mkdir('build', { recursive: true });
for (const [source, output] of [
  ['programs/sum_1_to_10.s', 'build/sum_1_to_10.hex'],
  ['programs/timer_irq.s', 'build/timer_irq.hex'],
  ['programs/uart_hello.s', 'build/uart_hello.hex'],
  ['programs/uart_rx.s', 'build/uart_rx.hex'],
  ['programs/uart_irq.s', 'build/uart_irq.hex'],
  ['programs/trap_user.s', 'build/trap_user.hex'],
  ['programs/user_protection.s', 'build/user_protection.hex'],
  ['programs/vm_trap_user.s', 'build/vm_trap_user.hex'],
]) {
  execFileSync('python3', [
    'tools/vynox128_asm.py', source, '--pad-to', '256', '-o', output,
  ], { stdio: 'inherit' });
}

async function runSimulation(top, paths) {
  console.log(`SIM: ${top}`);
  const files = await Promise.all(
    paths.map(async (path) => ({ path, data: await readFile(path) })),
  );
  const result = await simulate({
    files,
    sources: paths.filter((path) => path.endsWith('.sv')),
    generation: '2012',
    top,
    timeoutMs: 30_000,
  });

  if (result.combinedOutput) process.stdout.write(result.combinedOutput);
  if (!result.success) {
    throw new Error(`Icarus failed for ${top} during ${result.stage ?? 'an unknown stage'}`);
  }
}

await runSimulation('tb_vynox128_alu', [
  'rtl/vynox128_alu.sv',
  'tb/tb_vynox128_alu.sv',
]);

await runSimulation('tb_vynox128', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'tb/tb_vynox128.sv',
]);

await runSimulation('tb_vynox128_isa', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'tb/tb_vynox128_isa.sv',
]);

await runSimulation('tb_vynox128_faults', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'tb/tb_vynox128_faults.sv',
]);

await runSimulation('tb_vynox128_trap', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'tb/tb_vynox128_trap.sv',
  'build/trap_user.hex',
]);

await runSimulation('tb_vynox128_pipeline', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'tb/tb_vynox128_pipeline.sv',
]);

await runSimulation('tb_vynox128_icache', [
  'rtl/vynox128_icache.sv',
  'tb/tb_vynox128_icache.sv',
]);

await runSimulation('tb_vynox128_dcache', [
  'rtl/vynox128_dcache.sv',
  'tb/tb_vynox128_dcache.sv',
]);

await runSimulation('tb_vynox128_soc', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_soc.sv',
  'build/sum_1_to_10.hex',
]);

await runSimulation('tb_vynox128_mmu', [
  'rtl/vynox128_mmu.sv',
  'tb/tb_vynox128_mmu.sv',
]);

await runSimulation('tb_vynox128_vm', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_vm.sv',
  'build/vm_trap_user.hex',
]);

await runSimulation('tb_vynox128_protection', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_protection.sv',
  'build/user_protection.hex',
]);

await runSimulation('tb_vynox128_irq', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_irq.sv',
  'build/timer_irq.hex',
]);

await runSimulation('tb_vynox128_uart', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_uart.sv',
  'build/uart_hello.hex',
]);

await runSimulation('tb_vynox128_uart_rx', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_uart_rx.sv',
  'build/uart_rx.hex',
]);

await runSimulation('tb_vynox128_uart_irq', [
  'rtl/vynox128_alu.sv',
  'rtl/vynox128_core.sv',
  'rtl/vynox128_uart.sv',
  'rtl/vynox128_dcache.sv',
  'rtl/vynox128_icache.sv',
  'rtl/vynox128_mpu.sv',
  'rtl/vynox128_mmu.sv',
  'rtl/vynox128_soc.sv',
  'tb/tb_vynox128_uart_irq.sv',
  'build/uart_irq.hex',
]);

await runSimulation('tb_vynox128_uart_unit', [
  'rtl/vynox128_uart.sv',
  'tb/tb_vynox128_uart_unit.sv',
]);
