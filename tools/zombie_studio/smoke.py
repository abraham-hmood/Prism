import bpy, os
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.mesh.primitive_uv_sphere_add(segments=64, ring_count=32, radius=1)
obj=bpy.context.object
mat=bpy.data.materials.new("M"); mat.use_nodes=True
bsdf=mat.node_tree.nodes["Principled BSDF"]
bsdf.inputs["Base Color"].default_value=(0.35,0.5,0.3,1)
obj.data.materials.append(mat)
bpy.ops.object.light_add(type='AREA', location=(3,-3,4)); bpy.context.object.data.energy=600
cam_data=bpy.data.cameras.new("C"); cam=bpy.data.objects.new("C",cam_data); bpy.context.collection.objects.link(cam)
cam.location=(0,-6,1); cam.rotation_euler=(1.4,0,0); bpy.context.scene.camera=cam
sc=bpy.context.scene
sc.render.engine='BLENDER_EEVEE'
sc.render.resolution_x=512; sc.render.resolution_y=512
sc.render.filepath=os.path.abspath("tools/zombie_studio/out/smoke.png")
bpy.ops.render.render(write_still=True)
print("RENDER_OK", sc.render.filepath)
