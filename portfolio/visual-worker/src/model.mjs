import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import Ajv from 'ajv';

const moduleDir = path.dirname(fileURLToPath(import.meta.url));
const defaultRepoRoot = path.resolve(moduleDir, '../../..');

export async function loadVisualContext(context) {
  const raw = await fs.readFile(context.input, 'utf8');
  const model = JSON.parse(raw);
  const repoRoot = path.resolve(context.repoRoot ?? process.env.PORTFOLIO_REPO_ROOT ?? defaultRepoRoot);
  return { ...context, repoRoot, model };
}

export async function validateVisualModel(context) {
  const schemaPath = path.join(context.repoRoot, 'portfolio/visuals/schemas/visual-model.schema.json');
  const schema = JSON.parse(await fs.readFile(schemaPath, 'utf8'));
  const ajv = new Ajv({ allErrors: true, strict: false });
  const validate = ajv.compile(schema);
  if (!validate(context.model)) {
    const message = validate.errors?.map(error => `${error.instancePath || '/'} ${error.message}`).join('; ') ?? 'unknown validation error';
    throw new Error(`visual-model.json failed schema validation: ${message}`);
  }
  return context;
}

export async function loadProfile(context) {
  const profilePath = path.join(context.repoRoot, 'portfolio/visuals/profiles', `${context.model.profile_id}.json`);
  const profile = JSON.parse(await fs.readFile(profilePath, 'utf8'));
  return { ...context, profile };
}
