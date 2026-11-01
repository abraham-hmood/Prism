package com.prism.launcher.videocraft

/**
 * The look of a film, chosen in one list and carried into every image prompt.
 *
 * ## Why a catalogue rather than a free-text style box
 *
 * The creator already had a style field, and it is still there -- but "style" asks the user to
 * describe a treatment in their own words, and most people describe a GENRE, not a rendering. A
 * dropdown of named looks turns that into one tap and makes the result repeatable: the same phrase
 * reaches the image model for every keyframe, so the film does not drift between shots.
 *
 * ## How a phrase is used
 *
 * Each entry contributes one fragment that the engine appends to the keyframe and reference prompts
 * as `Format: <phrase>.`. They are written to sit after the subject and camera, because a diffusion
 * model weights the end of a prompt more heavily and the look is what should hold across the film.
 * The phrases describe CAMERA, MEDIUM and LIGHT rather than plot, so they can be combined with any
 * story without fighting it.
 *
 * ## None means none
 *
 * The first entry is deliberate and is the default: no added phrasing at all, which is what every
 * project made before this list existed should keep rendering as.
 */
object VideoCraftStyles {

    data class VideoType(val label: String, val phrase: String)

    val types: List<VideoType> = listOf(
        VideoType("None", ""),

        // ── Live action ──────────────────────────────────────────────────────────────
        VideoType("Realistic", "naturalistic live-action photography, true-to-life proportions and materials"),
        VideoType("Photorealistic", "photorealistic rendering, accurate skin and surface detail"),
        VideoType("Cinematic", "cinematic widescreen composition, dramatic key light, shallow depth of field"),
        VideoType("Documentary", "documentary footage, natural light, an unobtrusive camera"),
        VideoType("Cinéma Vérité", "handheld vérité camera, available light, unposed subjects"),
        VideoType("Mockumentary", "deadpan interview framing, office fluorescents, a slowly zooming camera"),
        VideoType("Found Footage", "found-footage camcorder look, heavy grain, an unstable horizon"),
        VideoType("Home Video", "1980s home-video tape, washed colours, a burned-in date stamp"),
        VideoType("News Broadcast", "television news footage, field-reporter lighting, lower-third framing"),
        VideoType("Security Camera", "security-camera view, wide and grey, timecode in the corner"),
        VideoType("Dashcam", "dashcam perspective over a bonnet, glare and rain on glass"),
        VideoType("Bodycam", "bodycam footage, wide warped lens, flashlight sweep"),
        VideoType("Drone Footage", "aerial drone shot, sweeping movement, crisp midday light"),
        VideoType("Nature Documentary", "nature-documentary photography, telephoto depth, golden light"),
        VideoType("Macro Photography", "macro photography, extreme close focus, dew and texture"),
        VideoType("Studio Portrait", "studio portraiture, softbox key, seamless backdrop"),
        VideoType("Fashion Editorial", "fashion editorial, bold styling, hard flash, saturated colour"),
        VideoType("Street Photography", "candid street photography, layered city depth, overcast light"),
        VideoType("War Photography", "gritty photojournalism, dust, motion blur, desaturated tones"),
        VideoType("Sports Photography", "high-shutter sports photography, frozen action, stadium light"),
        VideoType("Underwater", "underwater photography, caustic light, suspended particles"),
        VideoType("Astrophotography", "astrophotography, star fields, long-exposure light trails"),
        VideoType("Surveillance Night", "night surveillance, infrared floodlight, washed-out greys"),
        VideoType("CCTV Timelapse", "time-lapse from a fixed high corner, streaking crowds"),

        // ── Animation and comics ─────────────────────────────────────────────────────
        VideoType("Anime", "Japanese cel animation: flat cel shading, clean outlines, expressive eyes"),
        VideoType("Shonen Anime", "high-energy shonen animation, speed lines, bold action poses"),
        VideoType("Shojo Anime", "soft shojo animation, sparkle accents, pastel palette, delicate linework"),
        VideoType("Retro 90s Anime", "1990s cel animation, hand-inked lines, slightly faded colour, film grain"),
        VideoType("Ghibli-esque Fantasy", "gentle hand-painted fantasy animation, lush greenery, warm light"),
        VideoType("Manga Panel", "black-and-white manga inking, screentone shading, dynamic lines"),
        VideoType("Chibi Cartoon", "chibi proportions, big heads, simple rounded shapes, bright colour"),
        VideoType("Cel-shaded 3D", "3D render with cel shading and drawn outlines"),
        VideoType("Western Cartoon", "western 2D cartoon, exaggerated shapes, flat bright colour"),
        VideoType("Comic Book Ink", "comic-book illustration, heavy inks, halftone dots, primary colour"),
        VideoType("Graphic Novel Noir", "high-contrast graphic-novel inking, heavy shadow, a limited palette"),
        VideoType("Watercolour Animation", "animated watercolour: soft washes, bleeding edges, paper texture"),
        VideoType("Puppet Animation", "marionette puppetry, fabric textures, theatrical lighting"),
        VideoType("Stop-motion Claymation", "claymation stop-motion, thumbprints in the plasticine, small-set lighting"),
        VideoType("Felt and Wool", "felted-wool stop-motion, fuzzy fibres, craft-table sets"),
        VideoType("Paper Cut-out", "cut-paper collage animation, layered card edges, flat lighting"),
        VideoType("Shadow Play", "backlit silhouette shadow puppetry, a single warm lamp"),
        VideoType("Rotoscoped", "rotoscoped animation traced from live footage, painted-over realism"),
        VideoType("Pixel Art", "pixel art, limited palette, visible pixels, 16-bit era"),
        VideoType("8-bit", "8-bit console graphics, chunky sprites, ordered dithering"),
        VideoType("Voxel World", "voxel construction, cubic blocks, isometric feel"),
        VideoType("Low-poly 3D", "low-polygon 3D render, faceted surfaces, flat shading"),
        VideoType("Mecha Anime", "anime mecha, cockpit glow, towering robots against a city"),
        VideoType("Magical Girl", "magical-girl transformation, ribbons and sparkles, saturated pastels"),
        VideoType("Cyberpunk Anime", "cyberpunk anime, neon kanji signage, rain and chrome"),

        // ── Cinematic genres ─────────────────────────────────────────────────────────
        VideoType("Blockbuster Action", "big-budget action framing, practical explosions, anamorphic flare"),
        VideoType("Superhero Film", "superhero staging, larger-than-life poses, city scale, hero lighting"),
        VideoType("Epic Historical", "historical epic, vast sets, period detail, crowds at scale"),
        VideoType("Period Drama", "period drama, candle and window light, restrained camera"),
        VideoType("Western", "dusty western, wide frontier vistas, low sun, leather and denim"),
        VideoType("Spaghetti Western", "spaghetti western, extreme close-ups on eyes, bleached colour"),
        VideoType("Neo-noir", "neo-noir, wet streets, neon reflections, long shadows"),
        VideoType("Film Noir", "1940s film noir, hard chiaroscuro, cigarette smoke, venetian blinds"),
        VideoType("Crime Thriller", "crime-thriller staging, desaturated interiors, uneasy framing"),
        VideoType("Heist Movie", "heist-movie cool, team staging, crisp suits, city night"),
        VideoType("Spy Thriller", "spy thriller, international locations, surveillance tension"),
        VideoType("Psychological Thriller", "psychological thriller, tight close-ups, cold light, off-centre frames"),
        VideoType("Horror", "horror staging, deep shadow, a single light source, unsettling stillness"),
        VideoType("Slasher", "slasher-film look, moonlit woods, grainy 35 mm"),
        VideoType("Gothic Horror", "gothic horror, candlelit mansion, heavy drapes, rolling fog"),
        VideoType("Cosmic Horror", "cosmic horror, inhuman scale, sickly glow, tiny figures"),
        VideoType("Monster Movie", "rubber-suit monster movie, miniature city, practical destruction"),
        VideoType("Zombie Apocalypse", "post-outbreak ruin, abandoned cars, sickly green grade"),
        VideoType("Science Fiction", "science-fiction staging, clean industrial forms, projected light"),
        VideoType("Space Opera", "space opera, capital-ship scale, nebula backdrops, holographic interfaces"),
        VideoType("Cyberpunk", "cyberpunk, rain-slick megacity, neon signage, augmented chrome"),
        VideoType("Steampunk", "steampunk, brass and gears, coal smoke, Victorian engineering"),
        VideoType("Dieselpunk", "dieselpunk, riveted steel, art-deco machines, oily air"),
        VideoType("Solarpunk", "solarpunk, solar glass, planted terraces, optimistic daylight"),
        VideoType("Dystopian", "dystopian bleakness, bare concrete, surveillance cameras, grey sky"),
        VideoType("Post-apocalyptic", "post-apocalyptic wasteland, rust, scavenged vehicles, dust storms"),
        VideoType("Time Travel", "a time-travel frame, two eras clashing, double-exposed"),
        VideoType("Arthouse", "arthouse composition, negative space, a held frame, a quiet palette"),
        VideoType("Surrealist", "surrealist imagery, impossible juxtapositions, dream logic"),
        VideoType("Dreamlike", "hazy dream imagery, soft focus, floating light"),
        VideoType("Experimental", "experimental film, in-camera effects, layered projections, odd aspect ratio"),
        VideoType("Abstract", "abstract moving forms, colour fields, no recognisable subject"),

        // ── Fine art and design ──────────────────────────────────────────────────────
        VideoType("Impressionist", "impressionist painting, visible dabs, shimmering light"),
        VideoType("Expressionist", "expressionist distortion, jagged sets, exaggerated shadow"),
        VideoType("Baroque Painting", "baroque painting, chiaroscuro, rich drapery, raking light"),
        VideoType("Renaissance", "renaissance painting, balanced composition, sfumato, fresco tone"),
        VideoType("Romanticism", "romantic painting, storm skies, sublime scale"),
        VideoType("Art Deco", "art-deco geometry, gilded ornament, streamlined forms"),
        VideoType("Art Nouveau", "art-nouveau whiplash curves, floral ornament, stained glass"),
        VideoType("Bauhaus", "bauhaus design, primary colours, geometric type, clean planes"),
        VideoType("Brutalist", "brutalist concrete, monumental mass, harsh shadow"),
        VideoType("Minimalist", "minimalist staging, few elements, generous negative space"),
        VideoType("Pop Art", "pop-art print, bold outlines, ben-day dots, flat saturated colour"),
        VideoType("Psychedelic", "psychedelic melt, kaleidoscope colour, swirling pattern"),
        VideoType("Vaporwave", "vaporwave, pastel gradients, marble busts, CRT sheen"),
        VideoType("Synthwave", "synthwave, a chrome sun, scanlines, a purple horizon"),
        VideoType("Glitch Art", "glitch art, data-corrupted bands, chromatic splits"),
        VideoType("Blueprint", "technical blueprint rendering, white lines on cyan, dimension marks"),
        VideoType("Oil Painting", "oil painting, visible canvas weave, loaded brushwork"),
        VideoType("Gouache", "gouache illustration, opaque matte colour, crisp shapes"),
        VideoType("Charcoal Sketch", "charcoal sketch, smudged grain, rough gesture lines"),
        VideoType("Ink Wash", "sumi-e ink wash, wet bleeding strokes, sparse composition"),
        VideoType("Stained Glass", "stained-glass window, leaded panes, light transmitted through colour"),
        VideoType("Mosaic", "byzantine mosaic, tesserae tiles, a gold ground"),
        VideoType("Origami", "a folded-paper origami world, creased facets, paper grain"),
        VideoType("LEGO Bricks", "built from toy bricks, studded textures, minifigure scale"),
        VideoType("Miniature Diorama", "miniature diorama, tilt-shift depth, model-scale foliage"),
        VideoType("Toy Photography", "toy photography, macro scale, plastic sheen, a tabletop studio"),
        VideoType("Painted Backdrop", "shot against a painted studio backdrop, theatrical flatness"),

        // ── Fantasy and myth ─────────────────────────────────────────────────────────
        VideoType("High Fantasy", "high fantasy, mythic scale, banners and castles, painterly light"),
        VideoType("Dark Fantasy", "dark fantasy, ruined cathedrals, ashen light, a grim palette"),
        VideoType("Sword and Sorcery", "sword-and-sorcery staging, heroic poses, ancient ruins"),
        VideoType("Fairy Tale", "storybook fairy tale, ornate illustration, soft moral light"),
        VideoType("Norse Mythology", "norse myth imagery, runestones, longboats, cold iron"),
        VideoType("Greek Mythology", "greek myth imagery, marble statues, laurel, mediterranean sun"),
        VideoType("Urban Fantasy", "urban fantasy, magic leaking into a modern city, wet asphalt"),
        VideoType("Wuxia", "wuxia, silk robes, floating swordplay, misted mountains"),
        VideoType("Kaiju", "kaiju attack, miniature skyline, a looming silhouette, siren light"),
        VideoType("Supernatural", "supernatural atmosphere, unexplained light, cold breath"),
        VideoType("Folk Horror", "folk horror, standing stones, harvest fields, daylight menace"),

        // ── Formats and eras ─────────────────────────────────────────────────────────
        VideoType("Musical", "musical number, theatrical staging, spotlights, choreographed crowd"),
        VideoType("Music Video", "music-video styling, punchy grade, bold cover-art framing"),
        VideoType("Concert Film", "concert film, stage haze, beam lights, crowd silhouettes"),
        VideoType("Silent Film", "silent-film look, monochrome with a tint, painted intertitles, vignette"),
        VideoType("Slapstick Silent", "slapstick silent comedy, a sped-up gait, pratfall framing"),
        VideoType("Melodrama", "melodrama, saturated emotion, wind and rain, a long held gaze"),
        VideoType("Romance", "romantic staging, warm backlight, soft bokeh, a close two-shot"),
        VideoType("Teen Drama", "teen-drama naturalism, locker corridors, mixed fluorescent light"),
        VideoType("Coming of Age", "coming-of-age, late-afternoon light, small gestures, long takes"),
        VideoType("Family Film", "family-film warmth, bright even light, reassuring framing"),
        VideoType("Christmas", "christmastime, fairy lights, snow on the windowsill"),
        VideoType("Holiday Postcard", "holiday-postcard colour, a saturated sky, a banded sunset"),
        VideoType("Road Movie", "road-movie framing, a windshield horizon, roadside dust"),
        VideoType("Courtroom Drama", "courtroom staging, panelled wood, hard overhead light"),
        VideoType("Political Drama", "political drama, briefing rooms, flag and podium, grim daylight"),
        VideoType("War Film", "war-film look, mud, cold light, handheld urgency"),
        VideoType("Anti-war", "anti-war imagery, exhaustion, silence after shelling"),
        VideoType("Biopic", "biopic naturalism, period-accurate wardrobe, soft memory focus"),
        VideoType("Sports Drama", "sports drama, sweat and breath, a low-angle triumph"),
        VideoType("Cooking Show", "overhead cooking-show framing, tile and steel, warm practicals"),
        VideoType("Travelogue", "travelogue, golden-hour vistas, luggage, local colour"),
        VideoType("Infomercial", "infomercial cheer, hard flash, a white sweep, a hero product shot"),
        VideoType("Reality TV", "reality-television framing, a confessional close-up, mixed lighting"),
        VideoType("Sitcom", "sitcom staging, flat even light, three-camera eyelines"),
        VideoType("Sketch Comedy", "sketch-comedy staging, costume gags, a freeze-frame punchline"),
        VideoType("Talk Show", "late-night talk-show set, desk and couch, audience light"),
        VideoType("Gameplay Capture", "in-game capture look, HUD elements, sharp aliasing"),
        VideoType("Infrastructure", "industrial infrastructure, cabling and vents, work light"),
        VideoType("Airline Safety", "airline safety-video cleanliness, uniform light, even framing"),

        // ── Film stock and capture ───────────────────────────────────────────────────
        VideoType("8 mm Home Movie", "8 mm home movie, a circular vignette, sprocket weave"),
        VideoType("16 mm Film", "16 mm reversal stock, visible grain, a slight gate weave"),
        VideoType("35 mm Film", "35 mm film, fine grain, halation around the highlights"),
        VideoType("70 mm IMAX", "70 mm large-format clarity, immense depth of field"),
        VideoType("Black and White", "black-and-white photography, a rich tonal range"),
        VideoType("Sepia", "sepia tones, an aged photographic print"),
        VideoType("Technicolor", "three-strip technicolor, saturated primaries, studio sheen"),
        VideoType("Kodachrome", "kodachrome slide, warm reds, dense blues"),
        VideoType("Polaroid", "an instant-film square, soft focus, a chemical margin"),
        VideoType("Infrared", "infrared photography, white foliage, black sky"),
        VideoType("Thermal Imaging", "a thermal camera palette, heat gradients, a sensor crosshair"),
        VideoType("Night Vision", "a night-vision scope, green amplification, grain and bloom"),
        VideoType("Film Grain", "heavy film grain over a muted native colour"),
        VideoType("Lomography", "lomography, cross-processed colour, heavy vignette"),
        VideoType("Kaleidoscope", "kaleidoscopic mirroring, repeating facets"),
    )

    val none: VideoType = types.first()

    /** The entry for [label], or [none] when the label is unknown or blank. */
    fun of(label: String?): VideoType {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty()) return none
        return types.firstOrNull { it.label.equals(trimmed, true) } ?: none
    }

    /** The fragment [label] contributes to an image prompt; empty for [none]. */
    fun phraseFor(label: String?): String = of(label).phrase

    /** Every label, in list order, for a dropdown. */
    val labels: List<String> get() = types.map { it.label }
}
