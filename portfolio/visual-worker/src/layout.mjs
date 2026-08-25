import ELK from 'elkjs/lib/elk.bundled.js';

const elk = new ELK();

export async function layoutScenes(context) {
  const scenes = [];
  for (const scene of context.model.scenes) {
    if (scene.layout?.engine === 'elk' && scene.graph?.nodes?.length) {
      scenes.push(await layoutGraphScene(scene, context.profile));
    } else {
      scenes.push(scene);
    }
  }
  return { ...context, model: { ...context.model, scenes } };
}

async function layoutGraphScene(scene, profile) {
  const defaults = profile.layout?.graph;
  if (!defaults) {
    throw new Error(`Profile ${profile.id} does not define layout.graph`);
  }

  const nodeWidth = number(defaults.node_width, 'layout.graph.node_width');
  const nodeHeight = number(defaults.node_height, 'layout.graph.node_height');
  const padding = number(defaults.padding, 'layout.graph.padding');
  const graph = {
    id: scene.id,
    layoutOptions: {
      'elk.algorithm': scene.layout.algorithm || defaults.algorithm,
      'elk.direction': scene.layout.direction || defaults.direction,
      'elk.spacing.nodeNode': String(number(defaults.spacing_node_node, 'layout.graph.spacing_node_node')),
      'elk.layered.spacing.nodeNodeBetweenLayers': String(number(defaults.spacing_layer, 'layout.graph.spacing_layer')),
      'elk.padding': `[top=${padding},left=${padding},bottom=${padding},right=${padding}]`
    },
    children: scene.graph.nodes.map(node => ({ id: node.id, width: nodeWidth, height: nodeHeight })),
    edges: scene.graph.edges.map(edge => ({ id: edge.id, sources: [edge.source], targets: [edge.target] }))
  };

  const result = await elk.layout(graph);
  const sourceNodes = new Map(scene.graph.nodes.map(node => [node.id, node]));
  const nodes = (result.children ?? []).map(node => ({
    ...sourceNodes.get(node.id),
    layout: {
      x: node.x ?? 0,
      y: node.y ?? 0,
      width: node.width ?? nodeWidth,
      height: node.height ?? nodeHeight,
      label_x: (node.width ?? nodeWidth) / 2,
      label_y: (node.height ?? nodeHeight) / 2
    }
  }));

  const edges = (result.edges ?? []).map(edge => ({
    id: edge.id,
    source: edge.sources?.[0],
    target: edge.targets?.[0],
    path: edgePath(edge.sections ?? [])
  }));

  return {
    ...scene,
    graph: {
      ...scene.graph,
      nodes,
      edges,
      layout: {
        width: Math.ceil(result.width ?? nodeWidth),
        height: Math.ceil(result.height ?? nodeHeight)
      }
    }
  };
}

function edgePath(sections) {
  return sections.map(section => {
    const points = [section.startPoint, ...(section.bendPoints ?? []), section.endPoint].filter(Boolean);
    return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${round(point.x)} ${round(point.y)}`).join(' ');
  }).filter(Boolean).join(' ');
}

function round(value) {
  return Math.round(Number(value) * 100) / 100;
}

function number(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be numeric`);
  }
  return parsed;
}
