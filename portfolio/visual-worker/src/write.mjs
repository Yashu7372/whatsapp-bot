import fs from 'node:fs/promises';
import path from 'node:path';

export async function writeArtifacts(context) {
  await fs.mkdir(context.output, { recursive: true });
  const htmlPath = path.join(context.output, 'visual.html');
  const reportPath = path.join(context.output, 'visual-render.json');
  await fs.writeFile(htmlPath, context.html, 'utf8');

  const report = {
    status: 'PASS',
    renderer: 'portfolio-visual-worker',
    renderer_version: '1.0.0',
    contract_id: context.model.contract_id,
    profile_id: context.model.profile_id,
    scene_count: context.model.scenes.length,
    graph_scene_count: context.model.scenes.filter(scene => scene.graph?.nodes?.length).length,
    artifacts: ['visual.html', 'visual-render.json'],
    rendered_at: new Date().toISOString()
  };
  await fs.writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  return report;
}
