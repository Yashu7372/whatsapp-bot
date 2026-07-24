"""Render a constrained JSON shot plan with Blender.

Usage:
    blender -b [optional-template.blend] --python render_scene.py -- scene-plan.json output.mp4

The script uses named objects and actions from the opened .blend file when they
exist. It creates a simple 3D fallback set so the pipeline can be tested before
production character assets are added.
"""

import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


ALLOWED_ACTIONS = {"IDLE", "TALK", "WALK", "RUN", "WAVE", "POINT", "PRODUCT_TURN"}
CAMERA_PRESETS = {
    "CLOSE_UP": ((0.0, -5.2, 2.25), (0.0, 0.0, 2.15)),
    "MEDIUM": ((0.0, -7.0, 2.35), (0.0, 0.0, 1.75)),
    "WIDE": ((0.0, -10.0, 3.1), (0.0, 0.0, 1.4)),
    "TRACKING": ((-2.0, -7.5, 2.5), (0.0, 0.0, 1.65)),
    "PRODUCT": ((2.2, -5.5, 1.8), (1.35, 0.0, 1.2)),
}


def args_after_separator():
    if "--" not in sys.argv:
        raise RuntimeError("Expected scene-plan and output paths after --")
    args = sys.argv[sys.argv.index("--") + 1 :]
    if len(args) != 2:
        raise RuntimeError("Usage: -- scene-plan.json output.mp4")
    return Path(args[0]), Path(args[1])


def material(name, color, metallic=0.0, roughness=0.5):
    existing = bpy.data.materials.get(name)
    if existing:
        return existing
    value = bpy.data.materials.new(name)
    value.diffuse_color = (*color, 1.0)
    value.use_nodes = True
    shader = value.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1.0)
    shader.inputs["Metallic"].default_value = metallic
    shader.inputs["Roughness"].default_value = roughness
    return value


def add_primitive(kind, name, location, scale, mat, parent=None):
    if kind == "sphere":
        bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, location=location)
    elif kind == "cylinder":
        bpy.ops.mesh.primitive_cylinder_add(vertices=32, location=location)
    else:
        bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    obj.parent = parent
    return obj


def ensure_fallback_scene():
    character = bpy.data.objects.get("Character")
    product = bpy.data.objects.get("Product")
    if character and product:
        return character, product

    floor_mat = material("StudioFloor", (0.055, 0.07, 0.10), metallic=0.05, roughness=0.35)
    skin_mat = material("CharacterSkin", (0.92, 0.48, 0.24), roughness=0.7)
    shirt_mat = material("CharacterShirt", (0.08, 0.38, 0.90), roughness=0.45)
    dark_mat = material("CharacterDark", (0.025, 0.035, 0.06), roughness=0.6)
    product_mat = material("ProductMaterial", (0.9, 0.18, 0.33), metallic=0.25, roughness=0.22)

    if not bpy.data.objects.get("StudioFloor"):
        add_primitive("cube", "StudioFloor", (0, 0, -0.18), (6.5, 6.5, 0.18), floor_mat)

    if not character:
        character = bpy.data.objects.new("Character", None)
        bpy.context.collection.objects.link(character)
        body = add_primitive("cylinder", "Character_Body", (0, 0, 1.15), (0.58, 0.48, 0.85), shirt_mat, character)
        head = add_primitive("sphere", "Character_Head", (0, 0, 2.28), (0.53, 0.48, 0.58), skin_mat, character)
        add_primitive("sphere", "Character_Hair", (0, 0.03, 2.58), (0.54, 0.48, 0.27), dark_mat, character)
        mouth = add_primitive("cube", "Character_Mouth", (0, -0.47, 2.18), (0.16, 0.025, 0.035), dark_mat, character)
        left_arm = add_primitive("cylinder", "Character_Arm_L", (-0.78, 0, 1.35), (0.13, 0.13, 0.72), skin_mat, character)
        right_arm = add_primitive("cylinder", "Character_Arm_R", (0.78, 0, 1.35), (0.13, 0.13, 0.72), skin_mat, character)
        left_arm.rotation_euler.y = -0.12
        right_arm.rotation_euler.y = 0.12
        body["render_role"] = "character_body"
        head["render_role"] = "character_head"
        mouth["render_role"] = "mouth"

    if not product:
        product = add_primitive("cube", "Product", (1.5, 0.0, 1.0), (0.58, 0.42, 0.85), product_mat)
        bevel = product.modifiers.new("Soft product edges", "BEVEL")
        bevel.width = 0.12
        bevel.segments = 4

    return character, product


def ensure_camera():
    camera = bpy.data.objects.get("RenderCamera")
    if not camera:
        data = bpy.data.cameras.new("RenderCamera")
        camera = bpy.data.objects.new("RenderCamera", data)
        bpy.context.collection.objects.link(camera)
    bpy.context.scene.camera = camera
    camera.data.lens = 52
    return camera


def look_at(obj, target):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def ensure_lighting():
    if not bpy.data.objects.get("KeyLight"):
        data = bpy.data.lights.new("KeyLight", "AREA")
        data.energy = 1100
        data.shape = "DISK"
        data.size = 5.0
        light = bpy.data.objects.new("KeyLight", data)
        bpy.context.collection.objects.link(light)
        light.location = (-4.0, -4.0, 6.0)
        look_at(light, (0, 0, 1.3))
    if not bpy.data.objects.get("FillLight"):
        data = bpy.data.lights.new("FillLight", "AREA")
        data.energy = 700
        data.size = 4.0
        light = bpy.data.objects.new("FillLight", data)
        bpy.context.collection.objects.link(light)
        light.location = (4.0, -2.0, 4.0)
        look_at(light, (0, 0, 1.5))


def named_action(name):
    upper = name.upper()
    for action in bpy.data.actions:
        if action.name.upper() == upper or action.name.upper().endswith("_" + upper):
            return action
    return None


def add_rig_action(rig, action_name, start, end):
    action = named_action(action_name)
    if not action or rig.type != "ARMATURE":
        return False
    animation_data = rig.animation_data_create()
    track = animation_data.nla_tracks.get("ScriptActions") or animation_data.nla_tracks.new()
    track.name = "ScriptActions"
    strip = track.strips.new(f"{action_name}_{start}", start, action)
    strip.frame_end = end
    strip.repeat = max(1.0, (end - start) / max(1.0, action.frame_range[1] - action.frame_range[0]))
    return True


def animate_fallback(character, product, action_name, start, end):
    action_name = action_name if action_name in ALLOWED_ACTIONS else "IDLE"
    character.keyframe_insert("location", frame=start)
    character.keyframe_insert("rotation_euler", frame=start)
    product.keyframe_insert("rotation_euler", frame=start)

    if action_name in {"WALK", "RUN"}:
        distance = 1.5 if action_name == "WALK" else 2.8
        character.location.x += distance
        character.keyframe_insert("location", frame=end)
    elif action_name == "PRODUCT_TURN":
        product.rotation_euler.z += math.tau
        product.keyframe_insert("rotation_euler", frame=end)
    elif action_name in {"WAVE", "POINT"}:
        arm = bpy.data.objects.get("Character_Arm_R")
        if arm:
            arm.keyframe_insert("rotation_euler", frame=start)
            arm.rotation_euler.y = math.radians(70 if action_name == "WAVE" else 95)
            arm.keyframe_insert("rotation_euler", frame=start + max(2, (end - start) // 3))
            arm.rotation_euler.y = 0.12
            arm.keyframe_insert("rotation_euler", frame=end)

    if action_name == "TALK":
        mouth = bpy.data.objects.get("Character_Mouth")
        if mouth:
            base_scale = mouth.scale.copy()
            for frame in range(start, end + 1, 4):
                mouth.scale = base_scale
                mouth.keyframe_insert("scale", frame=frame)
                mouth.scale.z = base_scale.z * 2.4
                mouth.keyframe_insert("scale", frame=min(frame + 2, end))


def animate_camera(camera, preset, start, end):
    location, target = CAMERA_PRESETS.get(preset, CAMERA_PRESETS["MEDIUM"])
    camera.location = location
    look_at(camera, target)
    camera.keyframe_insert("location", frame=start)
    camera.keyframe_insert("rotation_euler", frame=start)
    if preset == "TRACKING":
        camera.location.x += 1.4
        look_at(camera, (1.0, 0.0, 1.65))
        camera.keyframe_insert("location", frame=end)
        camera.keyframe_insert("rotation_euler", frame=end)


def configure_render(scene, plan, output_path):
    try:
        scene.render.engine = "BLENDER_EEVEE_NEXT"
    except TypeError:
        scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = int(plan.get("width", 1080))
    scene.render.resolution_y = int(plan.get("height", 1920))
    scene.render.resolution_percentage = 100
    scene.render.fps = int(plan.get("fps", 24))
    scene.render.image_settings.file_format = "FFMPEG"
    scene.render.ffmpeg.format = "MPEG4"
    scene.render.ffmpeg.codec = "H264"
    scene.render.ffmpeg.constant_rate_factor = "MEDIUM"
    scene.render.ffmpeg.audio_codec = "AAC"
    scene.render.filepath = str(output_path)
    scene.render.film_transparent = False
    scene.world.color = (0.018, 0.025, 0.045)


def image_material(name, image_path):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    emission = nodes.new("ShaderNodeEmission")
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = bpy.data.images.load(str(image_path), check_existing=True)
    texture.interpolation = "Linear"
    links.new(texture.outputs["Color"], emission.inputs["Color"])
    links.new(emission.outputs["Emission"], output.inputs["Surface"])
    return mat


def render_storyboard(scene, plan, output_path):
    storyboard_images = plan.get("storyboardImages", {})
    if not storyboard_images:
        return False

    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)

    camera_data = bpy.data.cameras.new("StoryboardCamera")
    camera = bpy.data.objects.new("StoryboardCamera", camera_data)
    bpy.context.collection.objects.link(camera)
    scene.camera = camera
    camera.location = (0.0, 0.0, 5.0)
    camera.rotation_euler = (0.0, 0.0, 0.0)
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = 2.0

    frame = 1
    fps = scene.render.fps
    generated_planes = []
    for index, shot in enumerate(plan.get("shots", [])):
        image_path = storyboard_images.get(str(index))
        duration = max(1.0, float(shot.get("duration", 3)))
        end = frame + max(1, round(duration * fps)) - 1
        if image_path and Path(image_path).is_file():
            bpy.ops.mesh.primitive_plane_add(size=2, location=(0.0, 0.0, 0.0))
            plane = bpy.context.object
            plane.name = f"Storyboard_{index:03d}"
            plane.scale.x = 0.5625
            plane.data.materials.append(image_material(f"StoryboardMaterial_{index:03d}", image_path))
            generated_planes.append((plane, frame, end))

            plane.hide_render = True
            plane.keyframe_insert("hide_render", frame=max(1, frame - 1))
            plane.hide_render = False
            plane.keyframe_insert("hide_render", frame=frame)
            plane.keyframe_insert("hide_render", frame=end)
            plane.hide_render = True
            plane.keyframe_insert("hide_render", frame=end + 1)

            camera.data.ortho_scale = 2.0
            camera.data.keyframe_insert("ortho_scale", frame=frame)
            camera.data.ortho_scale = 1.88
            camera.data.keyframe_insert("ortho_scale", frame=end)
        frame = end + 1

    if not generated_planes:
        return False

    for curve in camera.data.animation_data.action.fcurves:
        for keyframe in curve.keyframe_points:
            keyframe.interpolation = "LINEAR"

    scene.frame_start = 1
    scene.frame_end = max(1, frame - 1)
    scene.render.filepath = str(output_path)
    bpy.ops.wm.save_as_mainfile(filepath=str(output_path.with_suffix(".blend")))
    bpy.ops.render.render(animation=True)
    return True


def main():
    plan_path, output_path = args_after_separator()
    with plan_path.open("r", encoding="utf-8") as source:
        plan = json.load(source)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    configure_render(scene, plan, output_path)
    if render_storyboard(scene, plan, output_path):
        return

    character, product = ensure_fallback_scene()
    camera = ensure_camera()
    ensure_lighting()

    rig = bpy.data.objects.get("CharacterRig")
    frame = 1
    fps = scene.render.fps
    for shot in plan.get("shots", []):
        duration = max(1.0, float(shot.get("duration", 3)))
        end = frame + max(1, round(duration * fps)) - 1
        action_name = str(shot.get("action", "TALK")).upper()
        camera_name = str(shot.get("camera", "MEDIUM")).upper()
        animate_camera(camera, camera_name, frame, end)
        if not rig or not add_rig_action(rig, action_name, frame, end):
            animate_fallback(character, product, action_name, frame, end)
        frame = end + 1

    scene.frame_start = 1
    scene.frame_end = max(1, frame - 1)
    bpy.ops.wm.save_as_mainfile(filepath=str(output_path.with_suffix(".blend")))
    bpy.ops.render.render(animation=True)


if __name__ == "__main__":
    main()
