"""
Rig, animate and export the zombie meshes, then render the in-game sprites.

This is the bridge between the authoring script and Prism:

  blender -b --factory-startup --python tools/zombie_studio/export_game_assets.py -- \
      --out tools/zombie_studio/out --assets app/src/main/assets/zombie

For each variant (male/female) and pose (stand/crawl) it

  1. builds the same high-poly body the studio script renders,
  2. joins it into one skinned mesh,
  3. builds an armature and binds it,
  4. keys a walking cycle (or a hand-over-hand crawl cycle),
  5. exports an animated .glb,
  6. renders a transparent 768x1536 sprite used by the built-in Android renderer.

Everything is generated from code; there are no external models or textures.
"""

from __future__ import annotations

import argparse
import math
import os
import shutil
import sys

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zombie_studio as studio  # noqa: E402


STAND_BONES = {
    "hips": ((0, 0, 0.94), (0, 0, 1.10), None),
    "spine": ((0, 0, 1.10), (0, 0, 1.30), "hips"),
    "chest": ((0, 0, 1.30), (0, 0, 1.44), "spine"),
    "neck": ((0, 0, 1.44), (0, 0, 1.52), "chest"),
    "head": ((0, 0, 1.52), (0, 0, 1.78), "neck"),
    "shoulder.L": ((-0.06, 0, 1.43), (-0.21, 0, 1.44), "chest"),
    "upperarm.L": ((-0.21, 0, 1.44), (-0.278, 0.012, 1.18), "shoulder.L"),
    "forearm.L": ((-0.278, 0.012, 1.18), (-0.305, 0.032, 0.935), "upperarm.L"),
    "shoulder.R": ((0.06, 0, 1.43), (0.21, 0, 1.44), "chest"),
    "upperarm.R": ((0.21, 0, 1.44), (0.278, 0.012, 1.18), "shoulder.R"),
    "forearm.R": ((0.278, 0.012, 1.18), (0.305, 0.032, 0.935), "upperarm.R"),
    "thigh.L": ((-0.10, 0, 0.90), (-0.112, -0.012, 0.50), "hips"),
    "shin.L": ((-0.112, -0.012, 0.50), (-0.110, 0, 0.115), "thigh.L"),
    "foot.L": ((-0.110, 0, 0.115), (-0.110, -0.16, 0.03), "shin.L"),
    "thigh.R": ((0.10, 0, 0.90), (0.112, -0.012, 0.50), "hips"),
    "shin.R": ((0.112, -0.012, 0.50), (0.110, 0, 0.115), "thigh.R"),
    "foot.R": ((0.110, 0, 0.115), (0.110, -0.16, 0.03), "shin.R"),
}

CRAWL_BONES = {
    "hips": ((0, 0.20, 0.64), (0, 0.02, 0.72), None),
    "spine": ((0, 0.02, 0.72), (0, -0.30, 0.98), "hips"),
    "chest": ((0, -0.30, 0.98), (0, -0.44, 1.06), "spine"),
    "neck": ((0, -0.44, 1.06), (0, -0.50, 1.12), "chest"),
    "head": ((0, -0.50, 1.12), (0, -0.68, 1.16), "neck"),
    "shoulder.L": ((-0.06, -0.42, 1.04), (-0.20, -0.44, 1.05), "chest"),
    "upperarm.L": ((-0.20, -0.44, 1.05), (-0.26, -0.63, 0.60), "shoulder.L"),
    "forearm.L": ((-0.26, -0.63, 0.60), (-0.27, -0.86, 0.16), "upperarm.L"),
    "shoulder.R": ((0.06, -0.42, 1.04), (0.20, -0.44, 1.05), "chest"),
    "upperarm.R": ((0.20, -0.44, 1.05), (0.26, -0.63, 0.60), "shoulder.R"),
    "forearm.R": ((0.26, -0.63, 0.60), (0.27, -0.86, 0.16), "upperarm.R"),
    "thigh.L": ((-0.105, 0.19, 0.59), (-0.125, 0.61, 0.20), "hips"),
    "shin.L": ((-0.125, 0.61, 0.20), (-0.135, 0.93, 0.115), "thigh.L"),
    "foot.L": ((-0.135, 0.93, 0.115), (-0.135, 1.05, 0.03), "shin.L"),
    "thigh.R": ((0.105, 0.19, 0.59), (0.125, 0.61, 0.20), "hips"),
    "shin.R": ((0.125, 0.61, 0.20), (0.135, 0.93, 0.115), "thigh.R"),
    "foot.R": ((0.135, 0.93, 0.115), (0.135, 1.05, 0.03), "shin.R"),
}


def join_body(parts) -> bpy.types.Object:
    meshes = [obj for obj in parts if obj.type == "MESH"]
    studio.activate(meshes[0])
    for obj in meshes[1:]:
        obj.select_set(True)
    bpy.ops.object.join()
    body = bpy.context.object
    body.name = "ZombieBody"
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return body


def build_rig(body: bpy.types.Object, bones: dict) -> bpy.types.Object:
    data = bpy.data.armatures.new("ZombieRig")
    rig = bpy.data.objects.new("ZombieRig", data)
    bpy.context.collection.objects.link(rig)
    studio.activate(rig)
    bpy.ops.object.mode_set(mode="EDIT")
    created = {}
    for name, (head, tail, parent) in bones.items():
        bone = data.edit_bones.new(name)
        bone.head = head
        bone.tail = tail
        created[name] = bone
    for name, (_, _, parent) in bones.items():
        if parent:
            created[name].parent = created[parent]
            created[name].use_connect = False
    for name, bone in created.items():
        if name in {"hips", "spine", "chest"}:
            radius = 0.135
        elif name in {"neck", "head"}:
            radius = 0.095
        elif name.startswith("thigh") or name.startswith("shin"):
            radius = 0.085
        elif name.startswith("foot"):
            radius = 0.060
        else:
            radius = 0.055
        bone.head_radius = radius
        bone.tail_radius = radius * 0.9
        bone.envelope_distance = radius * 1.4
    bpy.ops.object.mode_set(mode="OBJECT")

    bind_rigid(body, rig, bones)
    return rig


def bind_rigid(body: bpy.types.Object, rig: bpy.types.Object, bones: dict) -> None:
    """Bind every vertex to its nearest bone.

    Heat weighting fails on the fused, non-manifold garment and envelope weights
    smear the head into the chest during the crawl. Nearest-bone binding keeps the
    skull, hands and boots rigid, which is what a stop-motion zombie needs.
    """
    import numpy as np

    mesh = body.data
    count = len(mesh.vertices)
    coords = np.empty(count * 3, dtype=np.float32)
    mesh.vertices.foreach_get("co", coords)
    coords = coords.reshape(-1, 3)

    names = []
    best = np.full(count, 1e9, dtype=np.float32)
    owner = np.zeros(count, dtype=np.int32)
    for index, (name, (head, tail, _)) in enumerate(bones.items()):
        names.append(name)
        a = np.array(head, dtype=np.float32)
        b = np.array(tail, dtype=np.float32)
        ab = b - a
        denom = float(np.dot(ab, ab)) or 1.0
        t = np.clip(((coords - a) @ ab) / denom, 0.0, 1.0)
        closest = a + t[:, None] * ab
        distance = np.linalg.norm(coords - closest, axis=1)
        nearer = distance < best
        best[nearer] = distance[nearer]
        owner[nearer] = index

    body.vertex_groups.clear()
    for index, name in enumerate(names):
        group = body.vertex_groups.new(name=name)
        indices = np.where(owner == index)[0].tolist()
        if indices:
            group.add(indices, 1.0, "REPLACE")
    body.parent = rig
    modifier = body.modifiers.new("Armature", "ARMATURE")
    modifier.object = rig
    print("BOUND", count, "vertices to", len(names), "bones", flush=True)


def clear_pose(rig) -> None:
    for pose_bone in rig.pose.bones:
        pose_bone.rotation_mode = "XYZ"
        pose_bone.rotation_euler = (0, 0, 0)
        pose_bone.location = (0, 0, 0)


def key(rig, bone: str, frame: int, rotation=None, location=None) -> None:
    pose_bone = rig.pose.bones.get(bone)
    if pose_bone is None:
        return
    pose_bone.rotation_mode = "XYZ"
    if rotation is not None:
        pose_bone.rotation_euler = rotation
        pose_bone.keyframe_insert("rotation_euler", frame=frame)
    if location is not None:
        pose_bone.location = location
        pose_bone.keyframe_insert("location", frame=frame)


def walk_action(rig) -> bpy.types.Action:
    action = bpy.data.actions.new("Walk")
    rig.animation_data_create()
    rig.animation_data.action = action
    clear_pose(rig)
    deg = math.radians
    # A heavy, dragging gait: long stance, short swing, head lolling.
    frames = [1, 7, 13, 19, 25]
    for index, frame in enumerate(frames):
        phase = index / 4.0
        swing = math.sin(phase * math.tau)
        opposite = math.sin(phase * math.tau + math.pi)
        key(rig, "thigh.L", frame, (deg(24) * swing, 0, 0))
        key(rig, "thigh.R", frame, (deg(24) * opposite, 0, 0))
        key(rig, "shin.L", frame, (max(0.0, -swing) * deg(34), 0, 0))
        key(rig, "shin.R", frame, (max(0.0, -opposite) * deg(34), 0, 0))
        key(rig, "upperarm.L", frame, (deg(15) * opposite, 0, deg(3)))
        key(rig, "upperarm.R", frame, (deg(15) * swing, 0, -deg(3)))
        key(rig, "forearm.L", frame, (deg(10) + deg(8) * swing, 0, 0))
        key(rig, "forearm.R", frame, (deg(10) + deg(8) * opposite, 0, 0))
        key(rig, "spine", frame, (deg(4), 0, deg(3) * swing))
        key(rig, "chest", frame, (deg(6), 0, -deg(4) * swing))
        key(rig, "neck", frame, (deg(5), 0, deg(2) * opposite))
        key(rig, "head", frame, (deg(4) * swing, 0, deg(6) * opposite))
        key(rig, "hips", frame, (0, 0, deg(3) * swing),
            (0, 0, 0.018 * abs(swing)))
    return action


def crawl_action(rig) -> bpy.types.Action:
    action = bpy.data.actions.new("Crawl")
    rig.animation_data_create()
    rig.animation_data.action = action
    clear_pose(rig)
    deg = math.radians
    frames = [1, 7, 13, 19, 25]
    for index, frame in enumerate(frames):
        phase = index / 4.0
        reach = math.sin(phase * math.tau)
        opposite = math.sin(phase * math.tau + math.pi)
        # Each arm reaches forward, plants, then pulls the body past the hand.
        key(rig, "upperarm.L", frame, (deg(26) * reach, deg(6) * reach, deg(4)))
        key(rig, "upperarm.R", frame, (deg(26) * opposite, -deg(6) * opposite, -deg(4)))
        key(rig, "forearm.L", frame, (deg(18) - deg(16) * reach, 0, 0))
        key(rig, "forearm.R", frame, (deg(18) - deg(16) * opposite, 0, 0))
        key(rig, "thigh.L", frame, (deg(8) * opposite, 0, 0))
        key(rig, "thigh.R", frame, (deg(8) * reach, 0, 0))
        key(rig, "shin.L", frame, (deg(14) + deg(8) * reach, 0, 0))
        key(rig, "shin.R", frame, (deg(14) + deg(8) * opposite, 0, 0))
        key(rig, "hips", frame, (0, 0, deg(5) * reach), (0.010 * reach, 0, 0))
        key(rig, "spine", frame, (deg(3) * reach, 0, 0))
        key(rig, "head", frame, (deg(6), 0, deg(8) * opposite))
    return action


def export_glb(rig, body, path: str) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    rig.select_set(True)
    body.select_set(True)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        use_selection=True,
        export_animations=True,
        export_frame_range=True,
        export_anim_slide_to_zero=True,
        export_yup=True,
        export_apply=False,
    )
    print("GLB", path, flush=True)


def render_sprite(path: str, crawl: bool) -> None:
    scene = bpy.context.scene
    for obj in bpy.data.objects:
        if obj.name in {"Backdrop", "Floor"}:
            obj.hide_render = True
    scene.render.film_transparent = True
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1536
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.filepath = os.path.abspath(path)

    camera = bpy.data.objects.get("Camera")
    if camera is None:
        studio.add_camera("front")
        camera = bpy.data.objects.get("Camera")
    if crawl:
        camera.location = (0, -3.65, 0.78)
        target_z = 0.62
    else:
        camera.location = (0, -4.20, 1.02)
        target_z = 0.94
    camera.data.lens = 62
    direction = Vector((0, 0, target_z)) - camera.location
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    scene.camera = camera
    bpy.ops.render.render(write_still=True)
    print("SPRITE", path, flush=True)


def export_variant(male: bool, pose: str, out_dir: str, assets_dir: str, glb_dir: str) -> None:
    variant = "male" if male else "female"
    studio.reset_scene()
    studio.build_studio()
    print(f"BUILD {variant} {pose}", flush=True)
    parts = studio.build_body_v2(male, pose, 7)
    studio.apply_textures(parts)
    body = join_body(parts)

    rig = build_rig(body, STAND_BONES if pose == "stand" else CRAWL_BONES)
    action = walk_action(rig) if pose == "stand" else crawl_action(rig)
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = 25
    scene.frame_set(4)

    os.makedirs(glb_dir, exist_ok=True)
    glb_path = os.path.join(glb_dir, f"zombie_{variant}_{pose}.glb")
    export_glb(rig, body, glb_path)

    sprite_dir = os.path.join(assets_dir, "sprites")
    os.makedirs(sprite_dir, exist_ok=True)
    sprite_path = os.path.join(sprite_dir, f"zombie_{variant}_{pose}.png")
    render_sprite(sprite_path, pose == "crawl")

    # Keep the authoring renders next to the rest of the studio output.
    turntable = os.path.join(out_dir, f"{variant}_{pose}_final.png")
    shutil.copyfile(sprite_path, turntable)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="tools/zombie_studio/out")
    parser.add_argument("--assets", default="app/src/main/assets/zombie")
    parser.add_argument("--glb", default="tools/zombie_studio/game_assets")
    parser.add_argument("--variants", default="male,female")
    parser.add_argument("--poses", default="stand,crawl")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.assets, exist_ok=True)
    os.makedirs(args.glb, exist_ok=True)
    studio.TEXTURES = studio.generate_textures(os.path.join(args.out, "textures"), seed=10)
    for variant in [v.strip() for v in args.variants.split(",") if v.strip()]:
        for pose in [p.strip() for p in args.poses.split(",") if p.strip()]:
            export_variant(variant == "male", pose, args.out, args.assets, args.glb)


if __name__ == "__main__":
    main()
