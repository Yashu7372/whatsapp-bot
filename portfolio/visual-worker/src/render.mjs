import fs from 'node:fs/promises';
import path from 'node:path';
import Handlebars from 'handlebars';
import { marked } from 'marked';

Handlebars.registerHelper('join', (value, separator) => Array.isArray(value) ? value.join(separator) : '');
Handlebars.registerHelper('lower', value => String(value ?? '').toLowerCase());

export async function renderHtml(context) {
  const profile = context.profile;
  const templatePath = resolveRepoPath(context.repoRoot, profile.template);
  const stylesheetPath = resolveRepoPath(context.repoRoot, profile.stylesheet);
  const [pageTemplateText, stylesheet] = await Promise.all([
    fs.readFile(templatePath, 'utf8'),
    fs.readFile(stylesheetPath, 'utf8')
  ]);

  const pageTemplate = Handlebars.compile(pageTemplateText);
  const componentCache = new Map();
  const sceneHtml = [];

  for (const scene of context.model.scenes) {
    const configured = profile.components?.[scene.intent] ?? profile.components?.default;
    if (!configured) {
      throw new Error(`No component template configured for intent ${scene.intent} and no default exists.`);
    }
    let component = componentCache.get(configured);
    if (!component) {
      component = Handlebars.compile(await fs.readFile(resolveRepoPath(context.repoRoot, configured), 'utf8'));
      componentCache.set(configured, component);
    }
    const view = prepareScene(scene);
    sceneHtml.push(component({ scene: view }));
  }

  const html = pageTemplate({
    document: context.model.document,
    stylesheet,
    token_style: tokenStyle(profile.tokens ?? {}),
    scenes_html: sceneHtml.join('\n')
  });
  return { ...context, html };
}

function prepareScene(scene) {
  const showBody = scene.display?.body !== 'none';
  return {
    ...scene,
    show_body: showBody,
    body_html: showBody ? marked.parse(scene.content?.body ?? '', { async: false }) : ''
  };
}

function tokenStyle(tokens) {
  return Object.entries(tokens)
    .map(([name, value]) => `--${name.replaceAll('_', '-')}:${value}`)
    .join(';');
}

function resolveRepoPath(repoRoot, configuredPath) {
  if (!configuredPath) {
    throw new Error('Visual profile contains an empty file path.');
  }
  return path.isAbsolute(configuredPath) ? configuredPath : path.join(repoRoot, configuredPath);
}
