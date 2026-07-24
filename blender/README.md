# Blender script-to-video renderer

This renderer turns the existing generated shot JSON into a deterministic MP4.
It does not use an AI video model. The language model only chooses from a small
set of actions and camera presets.

## Runtime

Install Blender 4.x on the backend machine and make its executable available as
`blender`, or set:

```text
BLENDER_EXECUTABLE=/absolute/path/to/blender
BLENDER_PYTHON_SCRIPT=/absolute/path/to/blender/render_scene.py
BLENDER_TEMPLATE_DIR=/absolute/path/to/blender/templates
BLENDER_WORK_DIR=/absolute/path/to/local-renders
```

The backend runs:

```text
blender -b [template.blend] --python render_scene.py -- scene-plan.json video.mp4
```

## Production-quality templates

Put templates in `blender/templates` using the API template code as the filename,
for example `TALKING_PRESENTER.blend`.

Recommended naming contract:

- `CharacterRig`: the primary armature.
- `Product`: the product or prop root object.
- `RenderCamera`: the render camera.
- actions named `IDLE`, `TALK`, `WALK`, `RUN`, `WAVE`, `POINT`, and
  `PRODUCT_TURN`, or names ending in one of those values.

The bundled fallback scene is only a pipeline test. The visual quality comes
from a properly modeled, textured, lit, and rigged `.blend` template. Reusing
those templates gives consistent characters and backgrounds without paying for
Veo or another video-generation API.

## Current scope

- Vertical H.264 MP4 at 1080x1920 and 24 fps.
- Storyboard-image rendering with a slow camera push when approved shot images
  exist for the script.
- Reusable character actions and deterministic camera presets.
- Simple mouth animation in the fallback scene.
- Tenant-isolated asynchronous render jobs.

Voice generation, audio mixing, accurate phoneme lip-sync, and a template asset
uploader are intentionally separate follow-up features.
