import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { renderVisual } from '../src/pipeline.mjs';

const testDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(testDir, '../../..');

test('renders a portable visual model through the configured pipeline', async () => {
  const workspace = await fs.mkdtemp(path.join(repoRoot, 'portfolio/visual-worker/.test-'));
  try {
    const input = path.join(workspace, 'visual-model.json');
    const model = {
      schema_version: 1,
      contract_id: 'engineering-visual-v1',
      profile_id: 'engineering-explainer-v1',
      document: {
        id: 'TEST-001',
        title: 'Portable Visual Pipeline',
        number: 1,
        series_name: 'Test Series',
        series_length: 70
      },
      scenes: [
        {
          id: 'hero',
          intent: 'hero',
          order: 10,
          source: { section_id: 'hero', section_type: 'hero' },
          content: { title: 'Hero', body: '**Renderer-neutral** visual content.' },
          data: { article: { title: 'Portable Visual Pipeline', series_name: 'Test Series' } },
          display: { span: 'full', body: 'full' }
        },
        {
          id: 'flow',
          intent: 'system_flow',
          order: 20,
          source: { section_ids: ['a', 'b'] },
          content: { title: 'Composed flow', body: '' },
          data: {},
          display: { span: 'full', body: 'none' },
          layout: { engine: 'elk', algorithm: 'layered', direction: 'RIGHT' },
          graph: {
            nodes: [
              { id: 'a', label: 'Resolve context' },
              { id: 'b', label: 'Verify evidence' }
            ],
            edges: [{ id: 'a--b', source: 'a', target: 'b' }]
          }
        }
      ]
    };
    await fs.writeFile(input, `${JSON.stringify(model, null, 2)}\n`, 'utf8');
    const result = await renderVisual({ input, output: workspace });
    assert.equal(result.status, 'PASS');
    const html = await fs.readFile(path.join(workspace, 'visual.html'), 'utf8');
    assert.match(html, /Portable Visual Pipeline/);
    assert.match(html, /Resolve context/);
    const report = JSON.parse(await fs.readFile(path.join(workspace, 'visual-render.json'), 'utf8'));
    assert.equal(report.graph_scene_count, 1);
  } finally {
    await fs.rm(workspace, { recursive: true, force: true });
  }
});
