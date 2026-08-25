import { loadVisualContext, validateVisualModel, loadProfile } from './model.mjs';
import { layoutScenes } from './layout.mjs';
import { renderHtml } from './render.mjs';
import { writeArtifacts } from './write.mjs';

const DEFAULT_STAGES = [
  loadVisualContext,
  validateVisualModel,
  loadProfile,
  layoutScenes,
  renderHtml,
  writeArtifacts
];

export async function runPipeline(initialContext, stages = DEFAULT_STAGES) {
  let context = initialContext;
  for (const stage of stages) {
    context = await stage(context);
  }
  return context;
}

export async function renderVisual(context) {
  return runPipeline(context);
}
