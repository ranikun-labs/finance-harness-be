import { spawn } from 'node:child_process';

const host = '127.0.0.1';
const port = 4174;
const vite = spawn(
  process.execPath,
  [
    'node_modules/vite/bin/vite.js',
    '--config',
    'vite.finance-investment-lifecycle.config.ts',
    '--host',
    host,
    '--port',
    String(port),
    '--strictPort',
  ],
  { env: { ...process.env }, stdio: 'inherit' },
);

let stopping = false;
function stop(signal = 'SIGTERM') {
  if (stopping) return;
  stopping = true;
  vite.kill(signal);
}

process.once('SIGINT', () => stop('SIGINT'));
process.once('SIGTERM', () => stop('SIGTERM'));
vite.once('exit', (code) => {
  process.exitCode = code ?? 1;
});
