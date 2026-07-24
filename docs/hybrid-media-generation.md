# Hybrid storyboard image generation

The content studio uses a local-first provider router:

1. Send the shot to the configured local image worker.
2. If the local worker fails, use Gemini only when the per-shot and per-reel
   budget checks have already reserved enough spend.
3. Store the approved output in the existing tenant-scoped `media_assets`
   storage.
4. Compose storyboard images into video locally with Blender or FFmpeg.

When a Blender render is queued, the scene plan automatically includes the
newest completed storyboard image for each shot. Blender renders those frames
as a vertical sequence with a slow camera push. If no storyboard images exist,
the renderer continues to use the reusable 3D character/template path.

Paid image generation is disabled by default.

## Gemini configuration

```text
MEDIA_GEMINI_ENABLED=true
MEDIA_GEMINI_API_KEY=your-api-key
MEDIA_DEFAULT_SHOT_BUDGET_USD=0.20
MEDIA_DEFAULT_REEL_BUDGET_USD=1.00
MEDIA_HARD_MAX_SHOT_BUDGET_USD=0.50
MEDIA_HARD_MAX_REEL_BUDGET_USD=5.00
```

The defaults use `gemini-3.1-flash-image` for ECONOMY/BALANCED requests and
`gemini-3-pro-image` for QUALITY requests.

## Local worker contract

Enable the local worker with:

```text
MEDIA_LOCAL_ENABLED=true
MEDIA_LOCAL_BASE_URL=http://localhost:8188
```

The backend sends `multipart/form-data` to `POST /generate`:

- `prompt`: expanded scene prompt.
- `qualityMode`: `ECONOMY`, `BALANCED`, or `QUALITY`.
- `references`: zero to five character reference images.

The worker returns the generated image bytes as the response body and sets an
`image/jpeg` or `image/png` content type. This simple contract can be implemented
by a ComfyUI, PhotoMaker, InstantID, or FLUX Klein worker without coupling the
Java application to a specific local model.

## Character references

Create a character profile, then upload up to five reference images through the
existing endpoint:

```text
POST /api/v1/media/upload
assetType=CHARACTER_REFERENCE
refId=<character-profile-id>
file=<image>
```

Do not generate phone interfaces, captions, or payment text inside scene
images. Add exact text and UI overlays during local composition.
