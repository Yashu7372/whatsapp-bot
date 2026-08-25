#!/usr/bin/env node
import path from 'node:path';
import process from 'node:process';
import { renderVisual } from './pipeline.mjs';

function parseArgs(argv) {
  const [command, input, ...rest] = argv;
  if (command !== 'render' || !input) {
    throw new Error('Usage: node portfolio/visual-worker/src/index.mjs render <visual-model.json> --output <directory> [--repo-root <directory>]');
  }
  let output = path.dirname(path.resolve(input));
  let repoRoot;
  for (let index = 0; index < rest.length; index += 1) {
    if (rest[index] === '--output') {
      output = path.resolve(rest[index + 1]);
      index += 1;
    } else if (rest[index] === '--repo-root') {
      repoRoot = path.resolve(rest[index + 1]);
      index += 1;
    }
  }
  return { input: path.resolve(input), output, repoRoot };
}

try {
  const args = parseArgs(process.argv.slice(2));
  const result = await renderVisual(args);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  process.exitCode = result.status === 'PASS' ? 0 : 2;
} catch (error) {
  process.stderr.write(`${error.stack ?? error.message}\n`);
  process.exitCode = 2;
}
