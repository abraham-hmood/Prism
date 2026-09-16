"""Where does the Hair object's geometry land, and under which conditions?

The rigidity fix answered "does the hair travel with the head" (it does, exactly). The render says
the hair is in the WRONG PLACE to begin with, which rigidity cannot see. This compares the hair's
evaluated position under the four states that matter, all in ARMATURE space, against the head:

  A. as the .blend sits, posed, full modifier stack   -- what the artist sees
  B. armature flipped to REST, action still attached
  C. REST + action detached                           -- what gather_mesh() actually exports
  D. REST + action detached, modifiers disabled       -- the raw mesh under its object transform

If C disagrees with A on placement, the exporter is gathering the hair somewhere the artist never
put it, and that is the bug. Never saves the .blend.
"""
import os
import sys

import bpy

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import build_idle_animation as idle           # noqa: E402

rig = idle.rig
scene = bpy.context.scene


def bbox_in_armature(obj_name, use_modifiers=True):
    obj = bpy.data.objects[obj_name]
    to_arm = rig.matrix_world.inverted() @ obj.matrix_world
    if use_modifiers:
        depsgraph = bpy.context.evaluated_depsgraph_get()
        ev = obj.evaluated_get(depsgraph)
        mesh = ev.to_mesh()
        pts = [to_arm @ v.co.copy() for v in mesh.vertices]
        ev.to_mesh_clear()
    else:
        pts = [to_arm @ v.co.copy() for v in obj.data.vertices]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    return lo, hi


def show(tag):
    bpy.context.view_layer.update()
    for name in ("Hair", "Body"):
        if name not in bpy.data.objects:
            continue
        lo, hi = bbox_in_armature(name)
        c = [(lo[i] + hi[i]) / 2 for i in range(3)]
        s = [hi[i] - lo[i] for i in range(3)]
        print("<<< %-28s %-5s centre(%7.3f,%7.3f,%7.3f) size(%6.3f,%6.3f,%6.3f)"
              % (tag, name, c[0], c[1], c[2], s[0], s[1], s[2]))


def main():
    hair = bpy.data.objects.get("Hair")
    print("<<< Hair parent=%s type=%s bone=%r" %
          (hair.parent.name if hair.parent else None, hair.parent_type, hair.parent_bone))
    print("<<< Hair modifiers:", [(m.type, m.show_viewport) for m in hair.modifiers])
    print("<<< matrix_parent_inverse translation:",
          ["%.3f" % v for v in hair.matrix_parent_inverse.translation])
    print("<<< matrix_basis translation:", ["%.3f" % v for v in hair.matrix_basis.translation])
    print()

    # A -- exactly as the file sits.
    scene.frame_set(1)
    show("A posed+action+mods")

    # B -- rest pose, action still loaded.
    previous = rig.data.pose_position
    rig.data.pose_position = 'REST'
    show("B REST+action+mods")

    # C -- what gather_mesh does: REST and the action detached.
    previous_action = None
    if rig.animation_data:
        previous_action = rig.animation_data.action
        rig.animation_data.action = None
    show("C REST+noaction+mods")

    # D -- no modifiers at all, just the object transform.
    states = [(m, m.show_viewport) for m in bpy.data.objects["Hair"].modifiers]
    for m, _ in states:
        m.show_viewport = False
    show("D REST+noaction+nomods")
    for m, was in states:
        m.show_viewport = was

    # Restore, so nothing downstream inherits a probe's state.
    if rig.animation_data and previous_action is not None:
        rig.animation_data.action = previous_action
    rig.data.pose_position = previous
    bpy.context.view_layer.update()

    print()
    print("<<< The head bone sits at armature-space:",
          ["%.3f" % v for v in rig.data.bones["DEF-spine.006"].matrix_local.translation])


main()
