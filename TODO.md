# PhotoPix — implementation backlog

This file is the running gap list for the PhotoPix editor. The specification is the set of
documents in the repository root:

- `PictureThis.md` — the full toolbar/menu/panel catalog.
- `PhotoPixCompleteFeatureList.md` — the same catalog with the recent-release log.
- `PhotoPixOptionsFeatures.md` — every control inside every dialog.
- `PhotoPixVersionsFeatures.md` — every feature shipped from CS2 through 2020, with how it is used.

Every later change to `core/src/main/kotlin/com/prism/launcher/photopix/` or
`app/src/main/java/com/prism/launcher/photopix/` is expected to be checked against these four files.
The unchecked items below are the work that is still missing. An item is only checked when it is
implemented in the editor, reachable from its UI, and covered by a test or an exercised path.

---

## What this pass added

### Engine

- [x] All 27 layer blend modes, including Linear Burn, Darker Color, Lighter Color, Vivid Light,
  Linear Light, Pin Light, Hard Mix and Divide (`PixCore.kt`).
- [x] Raster layer masks with reveal/hide selection, reveal/hide all, density, feather, apply,
  delete and enable/disable; masks survive history snapshots and canvas resize/crop/rotate/flip
  (`PixDocument.kt`).
- [x] All ten layer effects with a shared renderer: Drop Shadow, Inner Shadow, Outer Glow,
  Inner Glow, Bevel & Emboss, Satin, Color Overlay, Gradient Overlay, Pattern Overlay and Stroke;
  multiple instances per layer, stackable (`PixLayerStyles.kt`).
- [x] Automatic corrections: Auto Tone, Auto Color, Equalize.
- [x] Selective Color (nine ranges, relative/absolute), Channel Mixer (monochrome and nine-source),
  Color Lookup presets plus a `.cube` parser and trilinear 3D-LUT application, Replace Color,
  HDR Toning (local adaptation), full Shadows/Highlights, Duotone/Tritone, Color & Vibrance,
  Color Balance with preserve luminosity (`PixAdjustmentsExtra.kt`).
- [x] 47 additional menu filters across Blur, Distort, Noise, Pixelate, Render, Sharpen, Stylize,
  Video and Other, plus Camera Raw–style processing and lens correction
  (`PixFiltersExtra.kt`).
- [x] The complete 47-effect Filter Gallery with Artistic, Brush Strokes, Distort, Sketch, Stylize
  and Texture groups (`PixGallery.kt`).
- [x] Select operations: Border, Smooth, Grow, Similar, Colour Range, Sky, Subject, Focus Area,
  Save Selection, Load Selection, Expand/Contract, Feather and Transform Selection
  (`PixSelectionTools.kt`).
- [x] Transforms: free transform (scale/rotate/shear), arbitrary rotation with canvas growth,
  four-corner perspective, grid warp with presets, content-aware scale by seam carving,
  puppet-warp displacement, auto-align by coarse-to-fine translation search, full Statistics stack
  modes and straighten (`PixTransforms.kt`).
- [x] Export writers: layered PSD (8-bit RGB, per-layer rectangle, blend, opacity, visibility,
  alpha and packed layer channels), uncompressed TIFF with alpha, 24-bit BMP, 32-bit TGA and PPM;
  a PSD composite reader (`PixExport.kt`).
- [x] New brush modes: saturate, desaturate, colour replacement and background erase
  (`PixBrush.kt`).
- [x] New tools exposed in the rail: Smudge, Sharpen, Sponge, Saturate, Desaturate, Colour
  Replacement, Background Eraser, Magic Eraser and Pencil (`PhotoPixView.kt`).
- [x] New panels: Select, Filter Gallery, Camera Raw, Transform, Layer Style, Layer Mask and a
  “More panels” hub; adjustments and filters lists extended with every new engine entry
  (`PhotoPixPanels.kt`).
- [x] Tests for blend modes, styles, masks, adjustments, all menu filters, all gallery effects,
  selection operations, transforms, PSD round-trip and the other writers
  (`core/src/test/kotlin/com/prism/core/PixProTest.kt`).

### Verification performed

- `:core:test` passes, including the new 18-test PhotoPix suite.
- `:app:compileDebugKotlin` passes.
- The PSD writer is round-tripped through the reader; TIFF/BMP/TGA/PPM headers and lengths are
  asserted.

### Honest fidelity notes for the implemented set

- Sky, Subject and Focus Area are deterministic colour/edge heuristics, not segmentation models.
- The Filter Gallery and several menu filters are original algorithms that produce the named
  look; they are not bit-exact reproductions of another application's output.
- Layer effects use one light, no contour editor, no pattern texture, no global-light link and no
  “scale effects” command.
- PSD export writes a flat layer list at 8 bits per channel. Groups, effects, masks, adjustment
  layers and 16/32-bit data are not represented in the file yet.
- The new panels expose the important parameters, not every slider listed in
  `PhotoPixOptionsFeatures.md`.

---

## 1. Toolbar

### 1.1 Move & arrange
- [ ] Move tool: auto-select layer/group under the pointer.
- [ ] Move tool: show transform controls on the canvas.
- [ ] Move tool: align and distribute buttons in the options bar (alignment exists only as a
  core operation, not a UI).
- [ ] Artboard tool (create, resize, duplicate, export artboards).
- [ ] Artboard auto-nesting and artboard name display.

### 1.2 Marquee
- [ ] Single Row Marquee.
- [ ] Single Column Marquee.
- [ ] Fixed ratio and fixed size selection modes.
- [ ] Anti-alias toggle and per-tool feather field exposed as in the options bar.

### 1.3 Lasso
- [ ] Polygonal Lasso as a distinct point-by-point tool (the current lasso is freehand only).
- [ ] Magnetic Lasso with width, contrast and frequency.
- [ ] Lasso options bar: feather, anti-alias, selection-mode buttons.

### 1.4 Smart selection
- [ ] Object Selection tool and Object Finder hover highlight.
- [ ] Quick Selection tool with brush size and sample-all-layers.
- [ ] Selection Brush tool with overlay colour and opacity.
- [ ] Magic Wand sample size, sample-all-layers, contiguous and anti-alias controls in the UI.
- [ ] Select Subject button in the options bar.
- [ ] Cloud-versus-device processing switch.

### 1.5 Crop & slice
- [ ] Perspective Crop tool.
- [ ] Slice tool and Slice Select tool.
- [ ] Content-Aware crop fill and Generative Expand.
- [ ] Crop overlay guides: Thirds, Grid, Diagonal, Triangle, Golden Ratio, Golden Spiral.
- [ ] Classic crop mode and Delete Cropped Pixels toggle.
- [ ] Crop straighten handle and auto-straighten.
- [ ] Interactive crop rectangle with handles and aspect presets.

### 1.6 Frame
- [ ] Shape frames beyond rectangles and ellipses.
- [ ] Generate Image inside a frame, with stroke, colour and style controls.
- [ ] Frame content scaling and replacement.

### 1.7 Eyedropper & measure
- [ ] Eyedropper sample size (point, 3×3, 5×5, 11×11, 31×31, 51×51, 101×101).
- [ ] Sample Current Layer / All Layers / All Layers No Adjustment.
- [ ] Sampling ring.
- [ ] Colour Sampler tool with up to ten persistent samplers.
- [ ] Ruler tool with Straighten Layer button.
- [ ] Note tool with persistent canvas notes.
- [ ] Count tool with numbered markers and a measurement log.

### 1.8 Healing & retouch
- [ ] Spot Healing Brush: Content-Aware, Create Texture and Proximity Match modes.
- [ ] Remove Tool with Find Distractions (wires and cables, people, general distractions,
  reflections) and Remove After Each Stroke.
- [ ] Generative AI Remove workflow.
- [ ] Healing Brush: sampled source versus pattern, diffusion slider, sample-all-layers.
- [ ] Patch Tool: Normal and Content-Aware modes, Structure and Color sliders, destination mode.
- [ ] Content-Aware Move Tool: Move and Extend modes, transform on drop.
- [ ] Red Eye Tool with pupil size and darken amount.

### 1.9 Brushes
- [ ] Brush tip shape editor: size, angle, roundness, hardness, spacing, flip X/Y.
- [ ] Shape Dynamics, Scattering, Texture, Dual Brush, Colour Dynamics, Transfer, Brush Pose.
- [ ] Noise, Wet Edges, Build-up, Smoothing and Protect Texture toggles.
- [ ] Bristle, erodible and airbrush tip types.
- [ ] Mixer Brush (wet, load, mix, flow, clean, sample-all-layers).
- [ ] Adjustment Brush that paints into an automatically created masked adjustment layer.
- [ ] Colour Replacement options bar (mode, limits, tolerance, anti-alias).
- [ ] Painting symmetry: Vertical, Horizontal, Dual Axis, Diagonal, Wavy, Circle, Spiral,
  Parallel Lines, Radial, Mandala.
- [ ] Pressure/tilt/rotation stylus support and the Brush HUD.
- [ ] Brush presets, preset groups, ABR import and legacy brushes.
- [ ] Tool presets and Reset Tool / Reset All Tools.

### 1.10 Stamp
- [ ] Pattern Stamp with impressionist mode.
- [ ] Clone Source panel with five sources, offset, scale, rotation and overlay preview.
- [ ] Clone Stamp “ignore adjustment layers” and aligned toggle.

### 1.11 History
- [ ] History Brush.
- [ ] Art History Brush with Tight Short through Loose Curl Long styles.
- [ ] History panel with snapshots, non-linear history and per-state restore.

### 1.12 Erasers
- [ ] Eraser modes: Brush, Pencil, Block; Erase to History.
- [ ] Background Eraser sampling modes (Continuous, Once, Background Swatch), limits
  (Discontiguous, Contiguous, Find Edges) and Protect Foreground Color.
- [ ] Magic Eraser tolerance, anti-alias, contiguous, sample-all-layers and opacity.

### 1.13 Fill
- [ ] Gradient types beyond linear and radial: Angle, Reflected, Diamond.
- [ ] Live, editable gradient layers.
- [ ] Perceptual / Linear / Classic interpolation, dither and reverse.
- [ ] Gradient presets panel and custom gradient editor.
- [ ] Paint Bucket tool with pattern fill, tolerance and contiguous options.

### 1.14 Focus & tone
- [ ] Dodge and Burn ranges: Shadows, Midtones, Highlights, Protect Tones.
- [ ] Sponge vibrance mode (saturate/desaturate exists; vibrance does not).
- [ ] Blur/Sharpen/Smudge strength and “finger painting” options bar.
- [ ] Tool-specific brush pressure controls.

### 1.15 Paths
- [ ] Pen Tool with rubber band, auto add/delete anchors.
- [ ] Freeform Pen and Magnetic Pen.
- [ ] Content-Aware Tracing Tool.
- [ ] Curvature Pen.
- [ ] Add Anchor Point, Delete Anchor Point and Convert Point tools.
- [ ] Path Selection and Direct Selection tools.
- [ ] Paths panel, work paths, path operations, path fill/stroke, path colour and width.
- [ ] Type on a path and type inside a shape.

### 1.16 Type
- [ ] Horizontal Type tool with a live type layer.
- [ ] Vertical Type tool.
- [ ] Horizontal Type Mask and Vertical Type Mask.
- [ ] Point-versus-paragraph conversion and type bounding-box reflow.
- [ ] On-canvas text editing, character panel and paragraph panel.

### 1.17 Shapes
- [ ] Triangle tool with live rounded corners.
- [ ] Polygon tool with sides, star ratio and smooth indents.
- [ ] Star tool with points and indent.
- [ ] Line tool with weight and arrowheads.
- [ ] Custom Shape tool and shape libraries.
- [ ] Live corner-radius widgets and editable vector shape layers.
- [ ] Fill and stroke swatches per shape; dashed strokes; shape combine operations.

### 1.18 Navigation
- [ ] Rotate View tool.
- [ ] Scrubby zoom, animated zoom and fit-on-screen gestures at desktop parity.
- [ ] Navigator panel with a movable proxy view.

### 1.19 Toolbar extras
- [ ] Edit Toolbar: customise groups, hide tools, add extras.
- [ ] Default Foreground/Background colours command (D).
- [ ] Switch Foreground/Background (X) — the swatch swap exists, the shortcut does not.
- [ ] Quick Mask Mode (Q) and quick-mask painting.
- [ ] Screen Modes (F): Standard, Full Screen with Menu Bar, Full Screen.
- [ ] Contextual Task Bar.
- [ ] Tool presets and Reset Tool / Reset All Tools.

---

## 2. Generative, assistant and machine-learning features

### 2.1 Generative (cloud-backed)
- [ ] Generative Fill with prompt, three variations and a variation browser.
- [ ] Generative Expand.
- [ ] Generate Image with style/structure references and effects presets.
- [ ] Generate Background.
- [ ] Generate Similar and Enhance Detail.
- [ ] Generative Workspace with generation history.
- [ ] Reference Image for Generative Fill.
- [ ] Generative Upscale and partner upscale models.
- [ ] Harmonize.
- [ ] Model picker and partner models.
- [ ] Rotate Object / rotate-and-tilt pixel layers into new perspectives.
- [ ] On-device generative model option.
- [ ] Content Credentials metadata on generated output.
- [ ] Send to video generation, import generated images and board round-trips.

### 2.2 AI assistant
- [ ] Conversational natural-language edits.
- [ ] Automatic layer cleanup and renaming.
- [ ] Recommendations and repetitive-task automation with hand-off to manual tools.

### 2.3 ML-assisted features
- [x] Select Subject (heuristic; model-grade quality still missing).
- [x] Select Sky (heuristic).
- [ ] Remove Background quick action.
- [ ] Object Selection / Object Finder.
- [ ] Select People and facial features.
- [ ] Select and Mask: Refine Hair and object-aware refine.
- [ ] Sky Replacement.
- [ ] Content-Aware Fill workspace (sampling area, colour/rotation/scale adaptation, mirror).
- [x] Content-Aware Scale (core; no protect-skin UI and slow on large images).
- [ ] Content-Aware Move and Content-Aware Patch.
- [ ] Find Distractions and reflection removal.
- [ ] AI Lens Blur from a depth map.
- [x] Auto-Align and Auto-Blend (core translation-only; no UI).
- [ ] Face-Aware Liquify.
- [ ] Neural Filters (all thirteen plus beta filters).
- [ ] Super Resolution / Enhance Details / AI Denoise.
- [ ] Match Font.
- [ ] Stock search inside the editor.
- [ ] Discover panel.
- [ ] Quick Actions: Remove Background, Blur Background, B&W Background, Enhance, Smooth Skin.

---

## 3. File menu

- [ ] New Document dialog with Photo, Print, Art & Illustration, Web, Mobile and Film & Video
  presets, templates and recent sizes.
- [ ] Open Recent and Clear Recent File List.
- [ ] Open As and Open As Smart Object.
- [ ] Close All, Close Others and Close and Go to Bridge.
- [ ] Save As, Save a Copy and layered Save (project format).
- [ ] Cloud documents: save, open, invite to edit and share for review.
- [ ] Revert and Version History.
- [ ] Quick Export with a configurable format.
- [ ] Export As dialog: PNG, JPG, GIF, SVG, WebP, AVIF, scale variants, canvas size, metadata and
  colour-space conversion.
- [ ] Export Preferences.
- [ ] Save for Web (Legacy): 2-up/4-up, colour tables, dithering, looping, WebP and WBMP.
- [ ] Artboards to Files, Artboards to PDF.
- [ ] Layer Comps to Files / PDF.
- [ ] Layers to Files.
- [ ] Colour Lookup Table export (CUBE, 3DL, CSP, ICC).
- [ ] Data Sets as Files, Paths to Illustrator.
- [ ] Render Video and Zoomify.
- [ ] Image Assets (Generator) with layer-name asset extraction.
- [ ] Share to other apps and Share for Review.
- [ ] Place Embedded and Place Linked.
- [ ] Package: collect linked files.
- [ ] Automate: Batch, PDF Presentation, Create Droplet, Crop and Straighten Photos,
  Contact Sheet II, Conditional Mode Change, Fit Image, batch Lens Correction, Merge to HDR Pro,
  Photomerge with all six layouts and edge filling.
- [ ] Scripts: Image Processor, Delete All Empty Layers, Flatten All Layer Effects, Flatten All
  Masks, Layer Comps to Files, Load Files into Stack, Load Multiple DICOM Files, Statistics,
  Script Events Manager and Browse.
- [ ] Import: Variable Data Sets, Video Frames to Layers, Notes, WIA/Images from Device,
  continuity camera.
- [ ] File Info XMP editor and templates.
- [ ] Print, Print One Copy, colour management, marks, functions and PostScript options.
- [ ] EPS, DCS 1.0/2.0, Photoshop 2.0 and PDF export.
- [ ] PSB large-document save.
- [ ] SVG export from vector content.
- [ ] WebP, AVIF, JPEG XL, HEIF/HEIC, JPEG 2000 and GIF writers.
- [ ] OpenEXR, Radiance HDR, DICOM, PCX, IFF, Scitex CT, Pixar and raw camera formats.
- [ ] Layered TIFF and layered PDF.
- [ ] Maximise PSD/PSB compatibility preference.

---

## 4. Edit menu

- [ ] Fade (opacity and blend mode of the last filter or adjustment).
- [ ] Cut, Copy, Copy Merged, Copy CSS and Copy SVG.
- [ ] Paste, Paste in Place, Paste Into, Paste Outside, Paste without Formatting.
- [ ] Clear.
- [ ] Discover search.
- [ ] Check Spelling and Find and Replace Text.
- [ ] Fill: Foreground, Background, Colour, Content-Aware, Pattern, History, Black, 50% Gray,
  White, preserve transparency, and scripted patterns (Brick Fill, Cross Weave, Random Fill,
  Spiral, Symmetry Fill, Place Along Path).
- [ ] Generative Fill command.
- [ ] Stroke dialog (width, colour, inside/centre/outside).
- [ ] Content-Aware Fill workspace.
- [x] Content-Aware Scale (core; options-bar protection missing).
- [ ] Puppet Warp UI (mesh, pins, rigid/normal/distort, pin depth).
- [ ] Perspective Warp UI (Layout and Warp, auto straighten).
- [ ] Interactive Free Transform on the canvas.
- [ ] Transform Again.
- [ ] Transform: Skew, Distort and Perspective as separate commands.
- [ ] Warp: the full preset set (Arc Lower, Arc Upper, Shell Lower, Shell Upper, Fish, Rise,
  Inflate, Squeeze) and a custom split grid.
- [ ] Auto-Align Layers UI and Auto-Blend Layers UI.
- [ ] Sky Replacement.
- [ ] Define Brush Preset, Define Pattern and Define Custom Shape.
- [ ] Purge Clipboard, Histories, All and Video Cache.
- [ ] Adobe PDF Presets.
- [ ] Preset Manager, Migrate Presets and Export/Import Presets.
- [ ] Remote Connections.
- [ ] Colour Settings, Assign Profile and Convert to Profile.
- [ ] Keyboard Shortcuts editor and HTML summary.
- [ ] Menus customisation (hide and colour-code items).
- [ ] Toolbar customisation.
- [ ] Preferences dialog.

---

## 5. Image menu

### 5.1 Mode
- [ ] Bitmap with all five dithering methods.
- [ ] Grayscale conversion.
- [ ] Duotone/Monotone/Tritone/Quadtone mode with ink curves and overprint colours.
- [ ] Indexed Color with the full palette and forced-colour set.
- [ ] CMYK, Lab and Multichannel modes.
- [ ] 8/16/32 bits per channel and HDR Toning on conversion.
- [ ] Colour Table.
- [ ] Working-space and ICC profile conversion.

### 5.2 Adjustments
- [x] Brightness/Contrast, Levels (single channel), Curves (polyline), Exposure, Vibrance,
  Hue/Saturation, Colour Balance, Black & White, Photo Filter, Channel Mixer, Colour Lookup,
  Invert, Posterize, Threshold, Gradient Map, Selective Colour, Shadows/Highlights, HDR Toning,
  Desaturate, Replace Colour, Equalize, Auto Tone, Auto Colour, Duotone, Color & Vibrance,
  preserve-luminosity Colour Balance.
- [ ] Brightness/Contrast Use Legacy and Auto.
- [ ] Levels per-channel controls, presets, auto options and eyedroppers.
- [ ] Curves per-channel, point/pencil modes, presets, clipping display and eyedroppers.
- [ ] Exposure eyedroppers.
- [ ] Hue/Saturation per-range controls, colorize, targeted adjustment and invert range.
- [ ] Black & White tint and per-hue eyedroppers.
- [ ] Channel Mixer presets and total readout.
- [ ] Colour Lookup Abstract and Device Link profiles plus dither and data/table order.
- [ ] Posterize/Threshold previews.
- [ ] Gradient Map full stop editor, dither, reverse and interpolation methods.
- [ ] Selective Colour on-image adjustment and per-range colour swatches.
- [ ] Shadows/Highlights radius, colour correction and save-defaults UI.
- [ ] HDR Toning methods, edge glow and toning curve.
- [ ] Match Colour.
- [ ] Replace Colour localized clusters and selection preview.
- [ ] Solid Colour, Gradient and Pattern fill layers.
- [ ] Adjustment presets (Portrait, Landscape, Photo Repair, Black & White, Cinematic, Creative).
- [ ] Adjustment layers for every adjustment.

### 5.3 Size and geometry
- [ ] Image Size resample methods: Automatic, Preserve Details 2.0, Preserve Details,
  Bicubic Smoother, Bicubic Sharper, Bicubic, Nearest Neighbor, Bilinear.
- [ ] Reduce Noise in Image Size, Scale Styles and Fit To presets.
- [ ] Canvas Size anchor UI, relative sizing and extension colour.
- [ ] Image Rotation: 180°, 90° CW/CCW (present), Arbitrary (present in Transform) and the
  Flip Canvas commands.
- [ ] Trim by transparent pixels, top-left colour and bottom-right colour.
- [ ] Reveal All.

### 5.4 Other
- [ ] Duplicate Image.
- [ ] Apply Image.
- [ ] Calculations.
- [ ] Variables: Define and Data Sets.
- [ ] Apply Data Set.
- [ ] Trap.
- [ ] Analysis: measurement scale, data points, record measurements, scale marker.
- [ ] Generative Upscale.

---

## 6. Layer menu and layer system

### 6.1 Structure
- [ ] Layer groups with nesting and group masks.
- [ ] Background from Layer.
- [ ] Artboards and frames as layer types.
- [ ] Layer via Copy and Layer via Cut from a selection.
- [ ] Duplicate Layer/Group with destination and name options.
- [ ] Delete Hidden Layers.
- [ ] Smart Objects: embedded, linked, library-linked, New via Copy, Edit Contents, Replace
  Contents, Export Contents, Embed, Convert to Linked, Convert to Layers, Stack Mode and relink.
- [ ] Fill layers: solid colour (exists as a flat fill), gradient and pattern.
- [ ] Adjustment layers: every adjustment, with masks and Properties-panel editing.
- [ ] Video layers: new from file, blank, insert/duplicate/delete frame, replace footage,
  interpret footage, restore frames and rasterize.
- [ ] Layer comps: create, update, apply, sync attributes, export to files/PDF.
- [ ] Clipping masks and release; clipping is stored on the model but not rendered.
- [ ] Layer links and Select Linked Layers.
- [ ] Layer locks: transparent pixels, image pixels, position, artboard, all.
- [ ] Layer filters: Kind, Name, Effect, Mode, Attribute, Colour, Smart Object, Selected, Artboard.
- [ ] Isolate Layers.
- [ ] Colour labels, thumbnail size/content and layer names editing in the panel.
- [ ] FX toggle, mask thumbnail, link icon and Properties-panel mask density/feather controls
  (density/feather exist inside the mask panel only).
- [ ] Copy/Paste/Clear Layer Style, Global Light, Create Layer(s), Hide All Effects, Scale Effects.
- [ ] Blending Options: Fill opacity, channel checkboxes, Knockout, Blend Interior Effects,
  Blend Clipped Layers, Transparency Shapes Layer, Layer/Vector Mask Hides Effects.
- [ ] Blend If sliders, including split sliders per channel.
- [ ] Styles panel and persisted style presets.
- [ ] Merge Visible, Stamp Visible, Flatten Image (Flatten exists on the Image panel) and
  Merge Layers.
- [ ] Matting: Color Decontaminate, Defringe, Remove Black Matte, Remove White Matte.
- [ ] Rasterize Type, Shape, Fill Content, Vector Mask, Smart Object, Video, Layer and All Layers.
- [ ] Align and Distribute commands with selection/canvas targets and spacing.
- [ ] Combine Shapes: Unite, Subtract Front, Unite at Overlap, Subtract at Overlap.
- [ ] Reverse layer order and Arrange commands.
- [ ] New Layer Based Slice.
- [ ] Pass Through group blending.

### 6.2 Layer types still missing
- [ ] Type layer.
- [ ] Shape/vector layer.
- [ ] Smart Object.
- [ ] Adjustment layer.
- [ ] Group.
- [ ] Artboard.
- [ ] Frame.
- [ ] Video layer.
- [ ] Live gradient layer.
- [ ] Generative layer.
- [ ] Smart Filter layer.
- [x] Pixel layer, background layer and masked raster layer.

---

## 7. Type menu and typography

- [ ] Type layers that stay editable.
- [ ] Character panel: family, style, size, leading, kerning (Metrics/Optical/manual), tracking,
  horizontal/vertical scale, baseline shift, colour and anti-aliasing.
- [ ] Paragraph panel: alignment (including justify variants), indents, space before/after,
  hyphenation, justification settings, hanging punctuation, Mojikumi, Kinsoku, tsume and
  Tate-chu-yoko.
- [ ] Faux Bold/Italic, All Caps, Small Caps, Superscript, Subscript, Underline and Strikethrough.
- [ ] Variable-font axes and OpenType SVG/colour/emoji fonts.
- [ ] OpenType features: ligatures, contextual alternates, swashes, oldstyle, stylistic sets,
  titling alternates, ornaments, ordinals and fractions.
- [ ] Glyphs panel with alternates and recently used.
- [ ] Character Styles and Paragraph Styles, with overrides.
- [ ] Bulleted and numbered lists in paragraph type.
- [ ] Warp Text with every style and bend/distortion controls.
- [ ] Match Font.
- [ ] Create Work Path and Convert to Shape.
- [ ] Convert point type to paragraph type and back.
- [ ] Vertical type and vertical type masks.
- [ ] Type on a path and inside a shape.
- [ ] Right-to-left, Indic and CJK language options.
- [ ] Find and Replace Text and Check Spelling with dictionaries.
- [ ] Manage Missing Fonts and Replace All Missing Fonts.
- [ ] Paste Lorem Ipsum and placeholder-text preference.
- [ ] Load/Save Default Type Styles.
- [ ] Dynamic Text reflow on bounding-box resize.
- [ ] Font filtering, favourites, similar fonts and classification.
- [ ] Contextual Task Bar type options.

---

## 8. Select menu

- [x] All, Deselect, Inverse, Feather, Border, Smooth, Expand/Contract, Grow, Similar, Colour
  Range (basic), Sky, Subject, Focus Area, Save and Load Selection.
- [ ] Reselect.
- [ ] All Layers, Deselect Layers, Find Layers and Isolate Layers.
- [ ] Colour Range: sampled colours, hue families, highlights/midtones/shadows, skin tones with
  Detect Faces, out-of-gamut, localized colour clusters, range, invert and saved presets.
- [ ] Select People and facial features.
- [ ] Select and Mask workspace: all seven view modes, Refine Edge Brush, Object Selection,
  Smart Radius, Smooth, Feather, Contrast, Shift Edge, Decontaminate Colors with amount,
  output-to selection/layer mask/new layer/new document, Refine Hair and remember-settings.
- [ ] Transform Selection on-canvas handles (core transform exists, no UI).
- [ ] Quick Mask Mode.
- [ ] Alpha-channel panel and per-channel editing.
- [ ] Load Selection add/subtract/intersect UI.
- [ ] Selection modes and feather exposed per tool.
- [ ] Focus Area add/subtract brushes, noise level, soften edge and output.
- [ ] Selection channel thumbnails and saved-selection persistence across documents.

---

## 9. Filter menu

### 9.1 Workspaces and smart filters
- [ ] Last Filter / Repeat.
- [ ] Convert for Smart Filters; re-editable, maskable, toggleable smart filters.
- [ ] Neural Filters (all thirteen plus beta/wait-list filters).
- [ ] Filter Gallery stacking of multiple effects, reordering and per-effect colour pickers.
- [ ] Adaptive Wide Angle constraint lines and correction types.
- [ ] Full Camera Raw workspace: Basic profiles, parametric and point Curve, Detail, Color Mixer,
  Point Color, Color Grading, Optics, Lens Blur, Geometry/Upright, Effects, Calibration and
  Masking with Subject/Sky/Background/People/Landscape/Brush/Gradients/Range masks.
- [ ] Camera Raw Remove/Heal/Clone/Red-eye/Pet-eye and distraction removal.
- [ ] Adaptive/presmium presets and HDR editing/output.
- [ ] Liquify: all ten tools, brush density/pressure/rate, freeze masks, reconstruct, mesh
  save/load, view modes, pin edges and Face-Aware controls.
- [ ] Vanishing Point: planes, tear-off, marquee, stamp, brush, transform, measure and export.
- [ ] 3D Generate Bump/Normal Map (removed upstream; excluded by the catalog's note).

### 9.2 Individual filters still incomplete
- [x] Average, Blur, Blur More, Box, Gaussian, Motion, Radial, Shape, Smart, Surface, Lens Blur,
  Field/Iris/Tilt-Shift/Path/Spin Blur (core).
- [ ] Lens Blur (Legacy) full depth-map source, iris blade curvature, specular controls and noise.
- [x] Displace core; missing the displacement-map chooser UI and Stretch/Tile/Wrap options.
- [x] Pinch, Polar Coordinates, Ripple, Shear, Spherize, Twirl, Wave, ZigZag.
- [x] Add Noise, Despeckle, Dust & Scratches, Median, Reduce Noise.
- [ ] Reduce Noise advanced per-channel mode.
- [x] Colour Halftone, Crystallize, Facet, Fragment, Mezzotint, Mosaic, Pointillize.
- [ ] Render Flame.
- [ ] Render Tree.
- [x] Clouds, Difference Clouds, Fibers, Lens Flare (soft radial stand-in), Lighting Effects.
- [ ] Lighting Effects light types (Spot, Point, Infinite), texture channel and presets.
- [x] Shake Reduction (unsharp-based), Sharpen, Sharpen Edges, Sharpen More, Smart Sharpen,
  Unsharp Mask.
- [ ] Smart Sharpen Shadows/Highlights fade, tonal width and radius.
- [x] Diffuse, Emboss, Extrude, Find Edges, Oil Paint, Solarize, Tiles, Trace Contour, Wind,
  Glowing Edges.
- [x] De-Interlace and NTSC Colours.
- [x] Custom convolution, High Pass, HSB/HSL, Maximum, Minimum, Offset.
- [ ] Maximum/Minimum Preserve Squareness/Roundness.
- [ ] Offset Set to Transparent/Repeat/Wrap Around options.
- [x] All 47 Filter Gallery entries (approximate fidelity).
- [ ] Exact Filter Gallery parameter sets per effect.

---

## 10. View, Window, plugins and panels

### 10.1 View
- [ ] Proof Setup (Working CMYK, plates, legacy RGB, monitor RGB, colour blindness, custom).
- [ ] Proof Colours and Gamut Warning.
- [ ] Pixel Aspect Ratio and correction.
- [ ] 32-bit Preview Options and HDR/EDR display support.
- [ ] Zoom In/Out, Fit Artboard, 100%, 200%, Print Size, Actual Size.
- [ ] Flip Horizontal (view only).
- [ ] Pattern Preview.
- [ ] Screen Mode menu.
- [ ] Extras and Show submenu: layer edges, selection edges, target path, grid, guides,
  artboard guides, count, smart guides, slices, notes, pixel grid, brush preview, mesh, edit pins,
  artboard names.
- [ ] Rulers.
- [ ] Snap and Snap To.
- [ ] Lock/Clear Guides, New Guide, New Guide Layout and New Guides from Shape.
- [ ] Lock/Clear Slices.

### 10.2 Plugins
- [ ] Plugins panel, browse/manage plugins, UXP development host, legacy 8BF host.

### 10.3 Window and panels
- [ ] Arrange: Tile, Consolidate All to Tabs, 2/3/4/6-up, Float, Cascade, Match Zoom/Location/
  Rotation/All, New Window.
- [ ] Workspaces: Essentials, Graphic and Web, Motion, Painting, Photography, Reset, New, Delete,
  Lock, keyboard shortcuts and menus.
- [ ] Application Frame, Options bar, Tools and Contextual Task Bar toggles.
- [ ] Actions panel with recording, playback, insert stop/menu item/path, button mode, conditional
  actions and built-in presets.
- [ ] Adjustments panel with presets.
- [ ] AI Assistant panel.
- [ ] Brush Settings panel with every dynamics section.
- [ ] Brushes panel with groups, legacy brushes and ABR import.
- [ ] Channels panel.
- [ ] Character and Paragraph panels.
- [ ] Character Styles and Paragraph Styles panels.
- [ ] Clone Source panel.
- [ ] Colour panel with wheel and RGB/HSB/CMYK/Lab/Grayscale/Web sliders.
- [ ] Comments panel.
- [ ] Comparison view.
- [ ] Content Credentials panel.
- [ ] Discover panel.
- [ ] Generated Image / results panel.
- [ ] Glyphs panel.
- [ ] Gradients panel with preset groups.
- [ ] Histogram panel (per channel, cache, statistics).
- [ ] History panel (snapshots, non-linear).
- [ ] Info panel (pointer values, samplers, document size, timing).
- [ ] Layer Comps panel.
- [ ] Libraries panel.
- [ ] Measurement Log.
- [ ] Navigator panel.
- [ ] Notes panel.
- [ ] Paths panel.
- [ ] Patterns panel.
- [ ] Properties panel for document/layer/type/shape/mask/smart object/adjustment/quick actions.
- [ ] Shapes panel.
- [ ] Styles panel.
- [ ] Swatches panel with groups, ASE import and recently used.
- [ ] Timeline panel (video and frame animation).
- [ ] Tool Presets panel.
- [ ] Version History panel.

---

## 11. Systems

### 11.1 Colour management
- [ ] ICC profile embedding and reading.
- [ ] Working spaces: sRGB, Display P3, wide-gamut and custom; OpenColorIO/ACES.
- [ ] Rendering intents and black-point compensation.
- [ ] Soft proofing and gamut warning.
- [ ] Colour Picker in HSB/RGB/Lab/CMYK/hex with web-only colours and colour libraries.
- [ ] Spot colour channels and overprint preview.
- [ ] HDR/EDR document display.

### 11.2 Formats and automation
- [x] PNG, JPEG, layered PSD (8-bit), TIFF, BMP, TGA, PPM.
- [ ] PSD/PSB layered read and write with groups, masks, effects, adjustment layers and 16/32-bit.
- [ ] PDF, EPS, DCS, SVG, WebP, AVIF, JPEG XL, HEIF, JPEG 2000, GIF, PCX, IFF, Scitex CT,
  Pixar, Radiance HDR, OpenEXR, DICOM, raw camera and PAM family.
- [ ] Video import and Render Video.
- [ ] Actions, droplets, batch, conditional actions.
- [ ] Scripting hosts: UXP (.psjs), ExtendScript (.jsx), AppleScript, VBScript/COM.
- [ ] Script Events Manager.
- [ ] Variables and data-driven graphics.
- [ ] Image Processor and Generator.

### 11.3 Preferences
- [ ] General, Interface, Workspace, Tools, History & Cloud, File Handling, Export, Performance,
  Scratch Disks, Cursors, Transparency & Gamut, Units & Rulers, Guides/Grid/Slices, Plugins,
  Type, Technology Previews and Product Improvement pages.
- [ ] Memory/history/cache/tile-size controls, GPU and OpenCL toggles.
- [ ] Auto-save interval, background save and recovery.
- [ ] Recent-file count.
- [ ] Custom transparency grid and gamut-warning colours.
- [ ] Ruler units, column size and point/pica size.
- [ ] UI scaling and theme levels.

### 11.4 Video, animation and painting extras
- [ ] Video timeline: clips, transitions, audio tracks, keyframed position/opacity/style/mask,
  motion presets, split clip, speed and Render Video.
- [ ] Frame animation: frames, tween, onion skin, delay, looping and animated-GIF export.
- [ ] Full stylus pressure/tilt/rotation/barrel support.
- [ ] Stroke smoothing modes (pulled string, catch-up, catch-up on stroke end, adjust for zoom).
- [ ] Painting symmetry.
- [ ] Brush tip previews and HUD.

### 11.5 Cloud, collaboration and ecosystem
- [ ] Cloud documents, offline access and named versions.
- [ ] Invite to Edit and Share for Review with comment pins.
- [ ] Creative Cloud-style libraries for colours, character styles, graphics, brushes, patterns
  and looks.
- [ ] Stock integration and licensing.
- [ ] Font activation.
- [ ] Content Credentials.
- [ ] Round-trip with the rest of the suite and 3D model placement.
- [ ] Beta channel and Technology Previews.

---

## 12. Options-by-dialog gaps

The engine exposes the important parameters; the following dialog controls are still absent from
the UI or the model. This section tracks `PhotoPixOptionsFeatures.md` directly.

- [ ] **Levels:** preset, per-channel selector, auto options dialog (five algorithms, clip %,
  target colours), eyedroppers and accurate-histogram command.
- [ ] **Curves:** preset, per-channel, pencil mode, Smooth, input/output fields, on-image tool,
  auto options, eyedroppers, Show Clipping, light/pigment display, simple/detailed grid, channel
  overlays, histogram, baseline and intersection line.
- [ ] **Exposure:** eyedroppers.
- [ ] **Hue/Saturation:** preset, six ranges, range sliders, add/subtract sampling, on-image tool
  and invert range.
- [ ] **Colour Balance:** tone radio buttons and preserve-luminosity toggle in one dialog.
- [ ] **Black & White:** preset, tint hue/saturation, auto and per-hue eyedroppers.
- [ ] **Photo Filter:** all named filter presets, arbitrary colour, preserve luminosity.
- [ ] **Channel Mixer:** presets, output-channel selector and total readout.
- [ ] **Colour Lookup:** 3DLUT file picker, abstract/device-link profiles, dither, data order and
  table order.
- [ ] **Gradient Map:** full gradient editor, method (Perceptual/Linear/Classic/Smooth/Stripes),
  dither and reverse.
- [ ] **Selective Colour:** colour-range dropdown with swatches, relative/absolute toggle.
- [ ] **Shadows/Highlights:** amount/tone/radius for both ends, colour correction, midtone
  contrast, black/white clip, save defaults and show-more-options.
- [ ] **HDR Toning:** method, edge-glow radius/strength, smooth edges, detail, toning curve.
- [ ] **Match Colour:** source document/layer, luminance, colour intensity, fade, neutralize,
  selection-use toggles and statistics load/save.
- [ ] **Replace Colour:** localized clusters, fuzziness, selection/image preview, result swatch.
- [ ] **Gaussian/Box/Motion/Radial/Shape/Smart/Surface blur:** every parameter in the catalog is
  exposed for most, but Radial's quality and blur centre and Smart's overlay-edge mode are not.
- [ ] **Lens Blur (Legacy):** depth-map source, invert, iris shape, blade curvature, rotation,
  specular brightness/threshold, noise amount/distribution and monochromatic toggle.
- [ ] **Blur Gallery:** Effects panel (Light Bokeh, Bokeh Colour, Light Range), Motion Effects
  strobe, Noise panel, on-canvas pins and feather handles.
- [ ] **Path Blur:** speed, taper, centred blur, end-point speed, blur shapes, basic/rear-sync flash.
- [ ] **Spin Blur:** blur angle, strobe strength/flashes/duration.
- [ ] **Displace:** horizontal/vertical scale, stretch/tile, wrap/repeat, map chooser.
- [ ] **Wave:** generators, wavelength min/max, amplitude min/max, scale H/V, sine/triangle/square,
  randomize and undefined-area mode.
- [ ] **ZigZag:** amount, ridges and Around-Centre/Out-from-Centre style.
- [ ] **Add Noise:** uniform/Gaussian and monochromatic toggles.
- [ ] **Reduce Noise:** advanced per-channel mode.
- [ ] **Colour Halftone:** max radius and four screen angles.
- [ ] **Mezzotint:** all eleven pattern types.
- [ ] **Clouds/Fibers/Lens Flare/Lighting Effects:** foreground/background pairing, lens types,
  light types, texture channel and presets.
- [ ] **Flame/Tree/Picture Frame:** all generative and frame options.
- [ ] **Shake Reduction:** blur-trace bounds, source noise, smoothing, artifact suppression and
  multiple estimation regions.
- [ ] **Smart Sharpen:** remove type (Gaussian/Lens/Motion), angle, shadows/highlights fade, tonal
  width and radius.
- [ ] **Emboss/Extrude/Tiles/Trace Contour/Wind:** all option values listed in the catalog.
- [ ] **Maximum/Minimum:** preserve squareness/roundness.
- [ ] **Offset:** set-to-transparent/repeat/wrap.
- [ ] **Custom:** editable 5×5 kernel with scale and offset.
- [ ] **Adaptive Wide Angle:** correction type, scale, focal length, crop factor, as-shot,
  constraint and polygon tools.
- [ ] **Lens Correction:** camera/lens profiles, distortion, four chromatic-aberration controls,
  vignette amount/midpoint, perspective, angle, scale and edge.
- [ ] **Filter Gallery:** per-filter 2–4 parameter dialogs and colour pickers.
- [ ] **Liquify:** all tools, brush fields, Face-Aware sliders, mesh load/save, mask options and
  view modes.
- [ ] **Brush Settings:** every section and toggle listed in the catalog.
- [ ] **Layer styles:** every field in `PhotoPixOptionsFeatures.md`, including contour, texture,
  pattern, gradient style, stroke fill type, global light, noise, jitter and range.
- [ ] **Colour Range / Focus Area / Select and Mask:** every dialog field.
- [ ] **Camera Raw:** every slider and tab (the current dialog exposes the Basic/Effects subset).
- [ ] **New Document / Image Size / Canvas Size / Export As / Save for Web / Print / JPEG / TIFF /
  PNG / PDF dialogs:** all fields and presets.

---

## 13. Version-history items still absent

These are the additions from `PhotoPixVersionsFeatures.md` that are not covered by the sections
above.

- [ ] Smart Objects (CS2) and the whole smart-object workflow.
- [ ] Interactive Image Warp and the full warp preset set.
- [ ] Vanishing Point (CS2) and its CS3 multi-plane extension.
- [ ] Merge to HDR / HDR Pro.
- [ ] Photomerge and the six layout modes.
- [ ] Animation palette / frame animation.
- [ ] Image Processor.
- [ ] Layer groups (CS2) — the model has a flat list.
- [ ] Menu customisation and Bridge integration.
- [ ] Quick Selection and Refine Edge.
- [ ] Smart Filters.
- [ ] Redesigned Curves and Brightness/Contrast.
- [ ] Auto-Align/Auto-Blend UI.
- [ ] Clone Source panel.
- [ ] Adjustments panel and Masks panel.
- [ ] Content-Aware Scale UI.
- [ ] Rotate View.
- [ ] 360° panorama editing.
- [ ] Content-Aware Fill and the dedicated workspace.
- [ ] Puppet Warp UI.
- [ ] Mixer Brush and bristle tips.
- [ ] HDR Toning UI.
- [ ] Perspective Crop and non-destructive Crop UI.
- [ ] Content-Aware Move and Patch.
- [ ] Blur Gallery on-canvas controls.
- [ ] Oil Paint (core exists).
- [ ] Background Save and Auto Recovery.
- [ ] Vector shape layers with strokes and dashes.
- [ ] Character/Paragraph Styles.
- [ ] Video Timeline in the standard edition.
- [ ] Preset migration and sharing.
- [ ] Scripted Patterns.
- [ ] Camera Shake Reduction with estimation regions.
- [ ] Image Size Preserve Details.
- [ ] Camera Raw as a filter.
- [ ] Generate Image Assets.
- [ ] Perspective Warp.
- [ ] Linked Smart Objects.
- [ ] Path and Spin Blur.
- [ ] Focus Area selection UI.
- [ ] Smart Guides with distance readouts.
- [ ] Font search, live preview and font activation.
- [ ] Layer Comps improvements.
- [ ] Package linked files.
- [ ] Flame, Picture Frame and Tree render filters.
- [ ] Artboards.
- [ ] Export As and Quick Export.
- [ ] Device Preview.
- [ ] Glyphs panel.
- [ ] Multiple strokes/shadows/overlays per layer (the model allows it; there is no “+” UI).
- [ ] Stroke overprint.
- [ ] SVG import/export.
- [ ] Select and Mask workspace.
- [ ] Face-Aware Liquify.
- [ ] Match Font.
- [ ] Content-Aware Crop.
- [ ] On-canvas glyph alternates.
- [ ] New Document dialog with templates.
- [ ] App-wide search.
- [ ] Properties panel.
- [ ] OpenType SVG and emoji fonts.
- [ ] Brush stroke smoothing.
- [ ] Variable fonts.
- [ ] Curvature Pen.
- [ ] Paint Symmetry.
- [ ] Lightroom cloud photos and Quick Share.
- [ ] Select Subject with model quality.
- [ ] Content-Aware Fill workspace.
- [ ] Frame tool as a layer type.
- [ ] Multiple undo by default (the editor has undo/redo but a single-step model in places).
- [ ] Proportional transform default and Shift toggle.
- [ ] Live blend-mode preview.
- [ ] Radial and Mandala symmetry.
- [ ] Colour Wheel.
- [ ] Lock Workspace.
- [ ] Home screen and Learn.
- [ ] Double-click text editing with Move.
- [ ] Math in number fields.
- [ ] Distribute Spacing.
- [ ] Flip document view.
- [ ] Placeholder text toggle.
- [ ] Object Selection tool.
- [ ] Multiple Content-Aware fills in one session.
- [ ] Custom Warp grids and split warp.
- [ ] Expanded Properties panel and Quick Actions.
- [ ] Gradients/Patterns/Shapes panels and reworked Swatches/Styles.
- [ ] Smart Object to Layers.
- [ ] Layer comps of Smart Objects in Properties.
- [ ] OpenType stylistic sets UI.
- [ ] Consistent proportional transform for all layer types.
- [ ] Cloud documents.
- [ ] Select Subject tuned for portraits.
- [ ] Rotatable patterns.
- [ ] Photoshop Improvement Program equivalent.
- [ ] Faster Object Selection and Shift Edge/Smooth acceleration.
- [ ] Every CS2–2020 point-release bug fix (the catalog lists these as fixes, not features; they are
  tracked here only for completeness).

---

## 14. Quality and performance work

- [ ] Move pixel processing off the UI thread and add progress/cancel for long filters.
- [ ] Tile or strip large documents instead of holding every layer as a full `IntArray`.
- [ ] Add a GPU/renderscript path for blends, blurs and transforms.
- [ ] Cache layer thumbnails and the flattened composite instead of re-rendering on every panel
  open.
- [ ] Replace the three-box Gaussian approximation with a true separable Gaussian option.
- [ ] Make content-aware scale incremental and previewable; the current seam carving is O(width ×
  height × seams).
- [ ] Raise the layer count, document size and history-budget limits once memory is tiled.
- [ ] Add colour-profile-aware conversion instead of treating every value as sRGB.
- [ ] Add 16- and 32-bit-per-channel documents; the engine is 8-bit.
- [ ] Add vector masks, layer groups and smart-object caches.
- [ ] Add a real text layout engine (shaping, bidi, OpenType) rather than bitmap text placement.
- [ ] Add a PSD reader for layers, masks and effects, not just the composite.
- [ ] Add undo grouping for slider drags (one history step per gesture, not per value).
- [ ] Add UI tests for the panels; the current suite covers the engine.
- [ ] Add screenshot-based regression tests for the Filter Gallery and layer styles.

---

## 15. Notes on the current implementation

- The engine is pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
- `PixDocument` remains a mutable document with whole-document snapshots and a byte-budgeted
  history; masks and styles are copied into each snapshot.
- Layer effects are rendered at flatten time, not baked into the layer, so editing the layer
  updates its effects.
- The new UI lives in the existing panels rather than a new activity, which keeps the Android
  editor and the desktop page on the same view.
- Any further edit to PhotoPix should start by reading the four specification files and updating
  this backlog in the same change.
