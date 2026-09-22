package com.prism.launcher.minigames

/**
 * Everything that can be drawn on the paper.
 *
 * ## The shape of the catalogue
 *
 * New building types arrive every two levels, from level 1 to level 500, which is two hundred and
 * fifty unlock tiers. Each era gets the slice of those tiers that matches its share of the level
 * range: medieval takes tiers 0–124, modern 125–155, futuristic 156–249. Buildings are authored in
 * order inside their era and dealt onto those tiers, so the order below IS the progression — moving
 * a line moves when it unlocks, and nothing else has to be touched.
 *
 * ## Why the stats are derived and the names are authored
 *
 * The opposite arrangement — authored stats, generated names — is how you end up with "Factory 7"
 * costing less than "Factory 3". Names carry identity and have to be written by a person; hit
 * points and build costs only have to be *ordered*, and an ordering is exactly what a formula over
 * the unlock tier gives you for free. So every building here has a real name and a category, and
 * [BuildingType.hitPoints] and friends are computed from where it sits in the progression.
 *
 * ## Caps
 *
 * `maxBase` is how many of a building you may own at the level it unlocks. It grows by one every
 * five levels afterwards, which is the cadence the whole game uses — see [Era.capAt]. A `maxBase`
 * of 1 means exactly one, forever: there is one town hall, and a second laboratory would be a
 * second town hall by another name.
 */
object BuildingCatalog {

    /**
     * What a building is for.
     *
     * [FORTIFICATION] is deliberately separate from [DEFENCE] even though both shoot back. A wall,
     * a gate and a bastion are the SHAPE of a base — they decide where an attacker can walk — while
     * a tower or a trap is a weapon bolted to the ground. Keeping them apart lets the build menu
     * group them the way a player thinks about them, and lets a later feature reason about the
     * perimeter without pulling in every turret.
     *
     * [AGRICULTURE] and [TRADE] are likewise split out of the old catch-all [RESOURCE] and
     * [CIVIC]: a market town is a real thing in this game now, with its own buildings, and burying
     * a granary among the mines made the farming half invisible.
     */
    enum class Category(val label: String) {
        CORE("Core"),
        MILITARY("Military"),
        DEFENCE("Defence"),
        FORTIFICATION("Fortification"),
        RESEARCH("Research"),
        HOUSING("Housing"),
        INDUSTRY("Industry"),
        RESOURCE("Resource"),
        AGRICULTURE("Agriculture"),
        TRADE("Trade"),
        INFRASTRUCTURE("Infrastructure"),
        CIVIC("Civic"),
    }

    /**
     * One kind of building.
     *
     * @param tier its unlock tier, 0..249. [unlockLevel] is the level it appears at.
     * @param maxBase how many may exist at [unlockLevel]; grows every five levels after that.
     * @param footprint how many cells square it occupies on the paper.
     */
    data class BuildingType(
        val id: String,
        val name: String,
        val category: Category,
        val age: Era.Age,
        val tier: Int,
        val maxBase: Int,
        val footprint: Int,
    ) {
        val unlockLevel: Int get() = (tier * 2 + 1).coerceAtMost(Era.MAX_LEVEL)

        /**
         * How much punishment it takes.
         *
         * Rises with the tier, and with the footprint, because a bigger drawing should take longer
         * to rub out. Defences and the town hall are tougher than a mill for the obvious reason:
         * a raid that flattens the walls as fast as the wheat is not a raid, it is weather.
         */
        val hitPoints: Int
            get() {
                val base = 120 + tier * 46
                val bulk = 1.0 + (footprint - 1) * 0.55
                val role = when (category) {
                    Category.CORE -> 3.2
                    Category.DEFENCE -> 1.9
                    Category.MILITARY -> 1.35
                    Category.RESEARCH, Category.INDUSTRY -> 1.15
                    else -> 1.0
                }
                return (base * bulk * role).toInt()
            }

        /** What it costs to put up, in XP. Building is progress, and progress is not free. */
        val buildCost: Long get() = (28L + tier * 11L) * footprint * footprint

        /**
         * What finishing it pays back. MORE than it cost, and that is the whole point.
         *
         * It used to be 0.62 of the cost, which made every building a net loss and the game a slow
         * slide backwards: a player who built steadily watched their XP fall and their town hall
         * level with it. That is the opposite of the design — "building more gives more XP" — and
         * on a device it showed up as level 5 becoming level 4 becoming level 3 while doing nothing
         * but building.
         *
         * The cost is still charged up front so that queueing six things is a real commitment; the
         * profit only arrives when the building does.
         */
        val buildReward: Long get() = (buildCost * 1.35).toLong()

        /** Seconds of work. The builders' huts are what parallelise this. */
        val buildSeconds: Int get() = (20 + tier * 14) * footprint

        /**
         * Defences shoot. Everything else is scenery with hit points.
         *
         * Fortifications count: a wall with an arrow slit in it is a thing that shoots, and a
         * bastion whose whole purpose is enfilading fire would be absurd as scenery. What separates
         * the two categories is what they are FOR, not whether they fight.
         */
        val isDefensive: Boolean
            get() = category == Category.DEFENCE || category == Category.FORTIFICATION

        /** Only housing holds civilians, and the population is what an economy is measured in. */
        val residents: Int get() = if (category == Category.HOUSING) 4 + tier / 3 else 0

        fun capAtLevel(level: Int): Int =
            if (maxBase <= 1) maxBase else Era.capAt(level, maxBase, step = 5, ceiling = 30)
    }

    // -- The authored progression ---------------------------------------------

    /**
     * Medieval, tiers 0–124: levels 1 to 249.
     *
     * Reads as a settlement growing outward — a hut and somewhere to drill, then food, then
     * materials, then walls, then the things walls make possible. `CORE` entries are the shape of
     * the opening the design asks for: a razed town hall, one builder, two training camps.
     */
    private val MEDIEVAL = """
        town_hall|Town Hall|CORE|1|3
        builders_hut|Builders' Hut|CORE|2|1
        training_camp|Soldier Training Camp|MILITARY|2|2
        weapons_research|Weapons Research|RESEARCH|1|2
        civilian_hut|Civilian Hut|HOUSING|4|1
        dirt_road|Dirt Road|INFRASTRUCTURE|12|1
        wheat_farm|Wheat Farm|AGRICULTURE|3|2
        lumber_camp|Lumber Camp|RESOURCE|2|2
        stone_quarry|Stone Quarry|RESOURCE|2|2
        storehouse|Storehouse|INDUSTRY|2|2
        village_well|Village Well|CIVIC|1|1
        palisade|Palisade Wall|DEFENCE|20|1
        watchtower|Watchtower|DEFENCE|2|1
        blacksmith|Blacksmith|INDUSTRY|1|2
        grain_mill|Grain Mill|INDUSTRY|2|2
        tannery|Tannery|INDUSTRY|1|2
        fletcher|Fletcher's Workshop|INDUSTRY|1|2
        archery_range|Archery Range|MILITARY|2|2
        longhouse|Longhouse|HOUSING|3|2
        goat_pen|Goat Pen|AGRICULTURE|2|2
        charcoal_kiln|Charcoal Kiln|INDUSTRY|2|1
        gatehouse|Gatehouse|DEFENCE|2|2
        arrow_tower|Arrow Tower|DEFENCE|3|1
        smithy_forge|Forge|INDUSTRY|1|2
        cart_shed|Cart Shed|INFRASTRUCTURE|2|2
        stables|Stables|MILITARY|1|3
        chapel|Chapel|CIVIC|1|2
        marketplace|Marketplace|CIVIC|1|3
        potter|Potter's Kiln|INDUSTRY|1|2
        beekeeper|Apiary|AGRICULTURE|2|1
        fishing_hut|Fishing Hut|AGRICULTURE|2|2
        rope_walk|Rope Walk|INDUSTRY|1|3
        moat_section|Moat Section|DEFENCE|14|1
        oil_pot_tower|Oil Pot Tower|DEFENCE|2|1
        barracks|Barracks|MILITARY|2|3
        armoury|Armoury|MILITARY|1|2
        stone_wall|Stone Wall|DEFENCE|24|1
        drawbridge|Drawbridge|DEFENCE|1|2
        granary|Granary|AGRICULTURE|2|2
        windmill|Windmill|INDUSTRY|2|2
        cooperage|Cooperage|INDUSTRY|1|2
        weaver|Weaver's Hall|INDUSTRY|1|2
        alchemist|Alchemist's Hut|RESEARCH|1|2
        scriptorium|Scriptorium|RESEARCH|1|2
        bath_house|Bath House|CIVIC|1|2
        crossbow_tower|Crossbow Tower|DEFENCE|3|1
        spike_trap|Spike Trap|DEFENCE|8|1
        watch_post|Watch Post|DEFENCE|4|1
        smokehouse|Smokehouse|INDUSTRY|2|2
        salt_pan|Salt Pan|AGRICULTURE|2|2
        iron_mine|Iron Mine|RESOURCE|2|3
        bloomery|Bloomery|INDUSTRY|2|2
        mason_yard|Mason's Yard|INDUSTRY|1|3
        carpenter|Carpenter's Shop|INDUSTRY|1|2
        siege_workshop|Siege Workshop|MILITARY|1|3
        ballista_tower|Ballista Tower|DEFENCE|2|2
        keep|Keep|DEFENCE|1|3
        barbican|Barbican|DEFENCE|1|3
        cobbled_road|Cobbled Road|INFRASTRUCTURE|16|1
        aqueduct|Aqueduct|INFRASTRUCTURE|4|3
        tithe_barn|Tithe Barn|AGRICULTURE|1|3
        mill_race|Mill Race|INFRASTRUCTURE|2|2
        dovecote|Dovecote|AGRICULTURE|2|1
        orchard|Orchard|AGRICULTURE|3|3
        vineyard|Vineyard|AGRICULTURE|2|3
        brewery|Brewery|INDUSTRY|1|2
        glassworks|Glassworks|INDUSTRY|1|2
        dye_works|Dye Works|INDUSTRY|1|2
        parchment_works|Parchment Works|INDUSTRY|1|2
        bell_tower|Bell Tower|CIVIC|1|1
        guildhall|Guildhall|CIVIC|1|3
        courthouse|Courthouse|CIVIC|1|3
        infirmary|Infirmary|CIVIC|1|2
        almshouse|Almshouse|HOUSING|2|2
        burgher_house|Burgher House|HOUSING|3|2
        manor|Manor House|HOUSING|1|3
        tenement_row|Tenement Row|HOUSING|3|3
        drill_yard|Drill Yard|MILITARY|1|3
        archer_school|Archery School|MILITARY|1|3
        knight_hall|Knights' Hall|MILITARY|1|3
        pike_hall|Pikemen's Hall|MILITARY|1|3
        mercenary_camp|Mercenary Camp|MILITARY|1|3
        war_kennel|War Kennel|MILITARY|1|2
        falconry|Falconry|MILITARY|1|2
        scout_post|Scout Post|MILITARY|2|1
        signal_beacon|Signal Beacon|DEFENCE|3|1
        murder_hole|Murder Hole|DEFENCE|6|1
        caltrop_field|Caltrop Field|DEFENCE|8|1
        pitfall|Covered Pitfall|DEFENCE|6|1
        boiling_vat|Boiling Vat|DEFENCE|3|1
        trebuchet_pad|Trebuchet Emplacement|DEFENCE|2|3
        mangonel_pad|Mangonel Emplacement|DEFENCE|2|2
        curtain_wall|Curtain Wall|DEFENCE|28|1
        corner_bastion|Corner Bastion|DEFENCE|4|2
        sally_port|Sally Port|DEFENCE|2|1
        armour_works|Armour Works|INDUSTRY|1|3
        crossbow_works|Crossbow Works|INDUSTRY|1|2
        shipyard|Shipyard|INDUSTRY|1|4
        harbour_crane|Harbour Crane|INFRASTRUCTURE|2|2
        warehouse_row|Warehouse Row|INDUSTRY|2|3
        counting_house|Counting House|CIVIC|1|2
        mint|Mint|CIVIC|1|2
        university|University|RESEARCH|1|4
        observatory|Observatory|RESEARCH|1|3
        library|Great Library|RESEARCH|1|3
        engineers_hall|Engineers' Hall|RESEARCH|1|3
        powder_mill|Powder Mill|INDUSTRY|1|3
        cannon_foundry|Cannon Foundry|INDUSTRY|1|3
        bombard_tower|Bombard Tower|DEFENCE|2|2
        star_fort|Star Fort Bastion|DEFENCE|2|3
        ravelin|Ravelin|DEFENCE|3|2
        arsenal|Arsenal|MILITARY|1|3
        parade_ground|Parade Ground|MILITARY|1|4
        quartermaster|Quartermaster's Store|MILITARY|1|2
        field_hospital|Field Hospital|MILITARY|1|2
        remount_depot|Remount Depot|MILITARY|1|3
        paved_highway|Paved Highway|INFRASTRUCTURE|20|1
        stone_bridge|Stone Bridge|INFRASTRUCTURE|4|2
        canal_lock|Canal Lock|INFRASTRUCTURE|2|2
        reservoir|Reservoir|INFRASTRUCTURE|2|3
        clock_tower|Clock Tower|CIVIC|1|2
        cathedral|Cathedral|CIVIC|1|4
        theatre|Playhouse|CIVIC|1|3
        merchant_quarter|Merchant Quarter|HOUSING|2|4
        artisan_quarter|Artisan Quarter|HOUSING|2|4
        citadel|Citadel|DEFENCE|1|4
        monastic_school|Monastic School|RESEARCH|1|3
        elementary_song_school|Elementary Song School|RESEARCH|2|2
        petty_school|Petty School|RESEARCH|2|2
        abacus_school|Abacus School|RESEARCH|1|2
        writing_school|Writing School|RESEARCH|2|2
        guild_apprentice_hall|Apprentice Hall|RESEARCH|2|2
        market_cross|Market Cross|TRADE|1|1
        weekly_market|Weekly Market|TRADE|2|3
        corn_exchange|Corn Exchange|TRADE|1|3
        wool_staple|Wool Staple|TRADE|1|3
        cloth_hall|Cloth Hall|TRADE|1|4
        merchant_house|Merchant House|TRADE|3|2
        pack_station|Pack Horse Station|TRADE|2|2
        toll_house|Toll House|TRADE|2|1
        weigh_house|Weigh House|TRADE|1|2
        exchange_bench|Money Changer's Bench|TRADE|2|1
        caravanserai|Caravanserai|TRADE|1|4
        fish_market|Fish Market|TRADE|2|2
        cattle_market|Cattle Market|TRADE|1|3
        spice_stall|Spice Stall|TRADE|3|1
        salt_house|Salt House|TRADE|2|2
        bonded_store|Bonded Store|TRADE|2|2
        guild_market|Guild Market|TRADE|1|3
        fair_ground|Fair Ground|TRADE|1|4
        hanse_kontor|Hanse Kontor|TRADE|1|3
        staple_port|Staple Port|TRADE|1|4
        ox_plough_field|Ox Plough Field|AGRICULTURE|4|3
        rye_field|Rye Field|AGRICULTURE|4|2
        barley_field|Barley Field|AGRICULTURE|4|2
        oat_field|Oat Field|AGRICULTURE|3|2
        flax_field|Flax Field|AGRICULTURE|3|2
        hemp_field|Hemp Field|AGRICULTURE|3|2
        turnip_field|Turnip Field|AGRICULTURE|3|2
        bean_plot|Bean Plot|AGRICULTURE|4|1
        cabbage_plot|Cabbage Plot|AGRICULTURE|4|1
        herb_garden|Herb Garden|AGRICULTURE|3|1
        hop_garden|Hop Garden|AGRICULTURE|2|2
        fallow_strip|Fallow Strip|AGRICULTURE|6|2
        common_pasture|Common Pasture|AGRICULTURE|3|4
        sheepfold|Sheepfold|AGRICULTURE|3|2
        cattle_byre|Cattle Byre|AGRICULTURE|3|2
        pig_sty|Pig Sty|AGRICULTURE|3|1
        poultry_yard|Poultry Yard|AGRICULTURE|3|1
        stud_paddock|Stud Paddock|AGRICULTURE|1|3
        hay_meadow|Hay Meadow|AGRICULTURE|3|3
        threshing_floor|Threshing Floor|AGRICULTURE|2|2
        winnowing_barn|Winnowing Barn|AGRICULTURE|2|2
        root_cellar|Root Cellar|AGRICULTURE|3|1
        cider_press|Cider Press|AGRICULTURE|1|2
        olive_grove|Olive Grove|AGRICULTURE|2|3
        mulberry_grove|Mulberry Grove|AGRICULTURE|2|3
        fish_pond|Stew Pond|AGRICULTURE|2|2
        eel_trap|Eel Trap|AGRICULTURE|2|1
        warren|Rabbit Warren|AGRICULTURE|2|2
        drainage_ditch|Drainage Ditch|AGRICULTURE|6|1
        water_meadow|Water Meadow|AGRICULTURE|2|3
        terrace_field|Terraced Field|AGRICULTURE|3|3
        assart_clearing|Assart Clearing|AGRICULTURE|3|3
        grange|Monastic Grange|AGRICULTURE|1|4
        demesne_barn|Demesne Barn|AGRICULTURE|1|3
        dry_moat|Dry Moat|FORTIFICATION|16|1
        earth_rampart|Earth Rampart|FORTIFICATION|18|1
        timber_stockade|Timber Stockade|FORTIFICATION|20|1
        motte|Motte|FORTIFICATION|1|3
        bailey_wall|Bailey Wall|FORTIFICATION|20|1
        shell_keep|Shell Keep|FORTIFICATION|1|3
        flanking_tower|Flanking Tower|FORTIFICATION|4|2
        corner_turret|Corner Turret|FORTIFICATION|4|1
        wall_walk|Wall Walk|FORTIFICATION|12|1
        hoarding|Timber Hoarding|FORTIFICATION|8|1
        machicolation|Machicolation|FORTIFICATION|6|1
        arrow_slit|Arrow Slit|FORTIFICATION|12|1
        portcullis|Portcullis|FORTIFICATION|2|1
        postern_gate|Postern Gate|FORTIFICATION|2|1
        outer_ward|Outer Ward Wall|FORTIFICATION|22|1
        inner_ward|Inner Ward Wall|FORTIFICATION|18|1
        concentric_wall|Concentric Wall|FORTIFICATION|20|1
        talus|Battered Talus|FORTIFICATION|10|1
        counterscarp|Counterscarp|FORTIFICATION|10|1
        glacis|Glacis|FORTIFICATION|8|2
        caponier|Caponier|FORTIFICATION|4|2
        redoubt|Redoubt|FORTIFICATION|3|2
        hornwork|Hornwork|FORTIFICATION|2|3
        crownwork|Crownwork|FORTIFICATION|2|3
        demilune|Demilune|FORTIFICATION|3|2
        tenaille|Tenaille|FORTIFICATION|3|2
        covered_way|Covered Way|FORTIFICATION|8|1
        palisade_fraise|Fraise|FORTIFICATION|10|1
        abatis|Abatis|FORTIFICATION|10|1
        chevaux_de_frise|Chevaux de Frise|FORTIFICATION|10|1
        barmkin|Barmkin|FORTIFICATION|3|2
        peel_tower|Peel Tower|FORTIFICATION|2|2
        broch|Broch|FORTIFICATION|1|3
        watch_beacon|Watch Beacon|FORTIFICATION|4|1
        chain_boom|Harbour Chain|FORTIFICATION|1|2
        siege_ditch|Siege Ditch|FORTIFICATION|8|1
        gabion_line|Gabion Line|FORTIFICATION|10|1
        artillery_bastion|Artillery Bastion|FORTIFICATION|3|3
        casemate|Casemate|FORTIFICATION|4|2
        powder_magazine|Powder Magazine|FORTIFICATION|2|2
        wattle_hut|Wattle Hut|HOUSING|6|1
        cruck_cottage|Cruck Cottage|HOUSING|5|1
        turf_house|Turf House|HOUSING|5|1
        reeve_house|Reeve's House|HOUSING|1|2
        bailiff_lodge|Bailiff's Lodge|HOUSING|1|2
        guest_hall|Guest Hall|HOUSING|1|3
        pilgrim_hostel|Pilgrim Hostel|HOUSING|1|3
        journeyman_lodging|Journeyman Lodging|HOUSING|3|2
        widows_row|Widows' Row|HOUSING|2|2
        town_house|Town House|HOUSING|4|2
        solar_block|Solar Block|HOUSING|2|2
        undercroft_dwelling|Undercroft Dwelling|HOUSING|3|2
        garret_row|Garret Row|HOUSING|3|2
        canons_house|Canons' House|HOUSING|1|3
        abbots_lodging|Abbot's Lodging|HOUSING|1|3
        dower_house|Dower House|HOUSING|1|2
        hunting_lodge|Hunting Lodge|HOUSING|1|3
        fishermans_cot|Fisherman's Cot|HOUSING|4|1
        shepherds_bothy|Shepherd's Bothy|HOUSING|4|1
        charcoalers_hut|Charcoaler's Hut|HOUSING|4|1
        cooper_shop|Cooper's Shop|INDUSTRY|1|2
        wheelwright|Wheelwright|INDUSTRY|1|2
        saddler|Saddler|INDUSTRY|1|2
        bowyer|Bowyer|INDUSTRY|1|2
        arrowsmith|Arrowsmith|INDUSTRY|1|2
        nailer|Nailer's Forge|INDUSTRY|2|1
        cutler|Cutler|INDUSTRY|1|2
        locksmith|Locksmith|INDUSTRY|1|2
        goldsmith|Goldsmith|INDUSTRY|1|2
        pewterer|Pewterer|INDUSTRY|1|2
        bellfounder|Bell Foundry|INDUSTRY|1|3
        limekiln|Lime Kiln|INDUSTRY|2|2
        brickworks|Brickworks|INDUSTRY|2|3
        tilery|Tilery|INDUSTRY|2|2
        fulling_mill|Fulling Mill|INDUSTRY|2|2
        tenter_ground|Tenter Ground|INDUSTRY|2|3
        dye_vats|Dye Vats|INDUSTRY|2|2
        silk_throwing|Silk Throwing Mill|INDUSTRY|1|3
        paper_mill|Paper Mill|INDUSTRY|1|3
        print_shop|Print Shop|INDUSTRY|1|2
        bookbinder|Bookbinder|INDUSTRY|1|2
        candle_works|Chandlery|INDUSTRY|2|2
        soap_house|Soap House|INDUSTRY|1|2
        glover|Glover|INDUSTRY|1|2
        furrier|Furrier|INDUSTRY|1|2
        shoemaker|Cordwainer|INDUSTRY|2|2
        joinery|Joinery|INDUSTRY|2|2
        turner_shop|Turner's Shop|INDUSTRY|2|2
        basketry|Basketry|INDUSTRY|2|1
        pottery_yard|Pottery Yard|INDUSTRY|2|2
        sawpit|Saw Pit|INDUSTRY|3|2
        tide_mill|Tide Mill|INDUSTRY|1|3
        horse_mill|Horse Mill|INDUSTRY|2|2
        oil_mill|Oil Mill|INDUSTRY|1|2
        malt_house|Malt House|INDUSTRY|2|2
        bake_house|Bake House|INDUSTRY|2|2
        butchery|Shambles|INDUSTRY|2|2
        forge_of_arms|Armourer's Forge|INDUSTRY|1|3
        mail_maker|Mail Maker|INDUSTRY|1|2
        helm_shop|Helm Shop|INDUSTRY|1|2
        shield_works|Shield Works|INDUSTRY|1|2
        siege_carpentry|Siege Carpentry|INDUSTRY|1|3
        rope_yard|Rope Yard|INDUSTRY|1|3
        sail_loft|Sail Loft|INDUSTRY|1|3
        tar_kiln|Tar Kiln|INDUSTRY|2|2
        silver_mine|Silver Mine|RESOURCE|1|3
        tin_mine|Tin Mine|RESOURCE|2|3
        lead_mine|Lead Mine|RESOURCE|2|3
        copper_mine|Copper Mine|RESOURCE|2|3
        coal_pit|Coal Pit|RESOURCE|2|3
        peat_cutting|Peat Cutting|RESOURCE|3|2
        clay_pit|Clay Pit|RESOURCE|3|2
        chalk_pit|Chalk Pit|RESOURCE|2|2
        slate_quarry|Slate Quarry|RESOURCE|2|3
        marble_quarry|Marble Quarry|RESOURCE|1|3
        flint_working|Flint Working|RESOURCE|2|2
        bog_iron_works|Bog Iron Works|RESOURCE|2|2
        saltern|Saltern|RESOURCE|2|2
        coppice|Coppice Wood|RESOURCE|3|3
        pannage_wood|Pannage Wood|RESOURCE|2|3
        deer_park|Deer Park|RESOURCE|1|4
        falcon_eyrie|Falcon Eyrie|RESOURCE|1|2
        oyster_beds|Oyster Beds|RESOURCE|2|3
        whaling_station|Whaling Station|RESOURCE|1|3
        amber_shore|Amber Shore|RESOURCE|1|3
        alum_works|Alum Works|RESOURCE|1|3
        saltpetre_beds|Saltpetre Beds|RESOURCE|1|2
        sulphur_pit|Sulphur Pit|RESOURCE|1|2
        ford_crossing|Ford|INFRASTRUCTURE|4|2
        timber_bridge|Timber Bridge|INFRASTRUCTURE|4|2
        causeway|Causeway|INFRASTRUCTURE|6|2
        corduroy_road|Corduroy Road|INFRASTRUCTURE|14|1
        drovers_road|Drovers' Road|INFRASTRUCTURE|12|1
        pilgrim_way|Pilgrim Way|INFRASTRUCTURE|10|1
        milestone|Milestone|INFRASTRUCTURE|8|1
        wayside_cross|Wayside Cross|INFRASTRUCTURE|6|1
        horse_ferry|Horse Ferry|INFRASTRUCTURE|2|2
        quay|Quay|INFRASTRUCTURE|3|3
        wharf|Wharf|INFRASTRUCTURE|3|3
        breakwater|Breakwater|INFRASTRUCTURE|2|4
        lighthouse|Lighthouse|INFRASTRUCTURE|1|2
        dovecote_tower|Dovecote Tower|INFRASTRUCTURE|2|1
        conduit_head|Conduit Head|INFRASTRUCTURE|2|1
        public_cistern|Public Cistern|INFRASTRUCTURE|2|2
        horse_trough|Horse Trough|INFRASTRUCTURE|4|1
        midden|Midden|INFRASTRUCTURE|4|1
        town_ditch|Town Ditch|INFRASTRUCTURE|8|1
        leper_house|Leper House|CIVIC|1|2
        almonry|Almonry|CIVIC|1|2
        guild_chapel|Guild Chapel|CIVIC|2|2
        parish_church|Parish Church|CIVIC|2|3
        friary|Friary|CIVIC|1|3
        priory|Priory|CIVIC|1|4
        abbey|Abbey|CIVIC|1|4
        hermitage|Hermitage|CIVIC|1|1
        shrine|Wayside Shrine|CIVIC|3|1
        charnel_house|Charnel House|CIVIC|1|2
        churchyard|Churchyard|CIVIC|1|3
        moot_hall|Moot Hall|CIVIC|1|3
        tollbooth|Tollbooth|CIVIC|1|2
        pillory|Pillory|CIVIC|1|1
        stocks|Stocks|CIVIC|1|1
        gaol|Gaol|CIVIC|1|2
        assize_hall|Assize Hall|CIVIC|1|3
        tithe_office|Tithe Office|CIVIC|1|2
        scriveners_office|Scrivener's Office|CIVIC|1|2
        physic_garden|Physic Garden|CIVIC|1|2
        barber_surgeon|Barber-Surgeon|CIVIC|1|2
        lazaretto|Lazaretto|CIVIC|1|3
        bear_pit|Bear Pit|CIVIC|1|2
        tilting_yard|Tilting Yard|CIVIC|1|4
        butts|Archery Butts|CIVIC|2|3
        tavern|Tavern|CIVIC|3|2
        inn|Inn|CIVIC|2|3
        bath_stew|Stews|CIVIC|1|2
        song_school|Song School|RESEARCH|1|2
        grammar_school|Grammar School|RESEARCH|1|3
        chantry_school|Chantry School|RESEARCH|1|2
        cathedral_school|Cathedral School|RESEARCH|1|3
        studium_generale|Studium Generale|RESEARCH|1|4
        astrolabe_works|Astrolabe Works|RESEARCH|1|2
        herbal_scriptorium|Herbal Scriptorium|RESEARCH|1|2
        anatomy_theatre|Anatomy Theatre|RESEARCH|1|3
        mappa_mundi_room|Map Room|RESEARCH|1|2
        alchemy_furnace|Alchemy Furnace|RESEARCH|1|2
        siege_drawing_office|Siege Drawing Office|RESEARCH|1|3
        gunners_school|Gunners' School|RESEARCH|1|3
        serjeanty_hall|Serjeanty Hall|MILITARY|1|3
        retinue_barrack|Retinue Barrack|MILITARY|2|3
        hobelar_stable|Hobelar Stable|MILITARY|1|3
        crossbow_corps|Crossbow Corps|MILITARY|1|3
        slinger_post|Slinger Post|MILITARY|2|2
        levy_muster|Levy Muster Field|MILITARY|1|4
        sergeants_hall|Sergeants' Hall|MILITARY|1|3
        squire_school|Squires' School|MILITARY|1|3
        armoury_store|Armoury Store|MILITARY|2|2
        fletching_shed|Fletching Shed|MILITARY|2|2
        bowstave_store|Bowstave Store|MILITARY|2|2
        siege_park|Siege Park|MILITARY|1|4
        pioneer_camp|Pioneer Camp|MILITARY|1|3
        sapper_gallery|Sapper Gallery|MILITARY|1|2
        provost_post|Provost's Post|MILITARY|1|2
        victualling_yard|Victualling Yard|MILITARY|1|3
        farrier_forge|Farrier's Forge|MILITARY|2|2
        war_dog_kennel|War Dog Kennel|MILITARY|1|2
        galley_shed|Galley Shed|MILITARY|1|4
        cog_harbour|Cog Harbour|MILITARY|1|4
        marine_barrack|Marine Barrack|MILITARY|1|3
    """.trimIndent()

    /**
     * Modern, tiers 125–155: levels 251 to 311.
     *
     * A short era by design — a quarter of the second half — so it is dense rather than long. It is
     * the only stretch of the game where an ordinary player sees a building type appear, get used,
     * and be superseded inside the same session.
     */
    private val MODERN = """
        steel_mill|Steel Mill|INDUSTRY|2|3
        power_station|Coal Power Station|INDUSTRY|1|4
        rail_yard|Rail Yard|INFRASTRUCTURE|1|4
        tarmac_road|Tarmac Road|INFRASTRUCTURE|24|1
        apartment_block|Apartment Block|HOUSING|4|3
        office_block|Office Block|INDUSTRY|3|3
        munitions_plant|Munitions Plant|INDUSTRY|2|3
        vehicle_works|Vehicle Works|INDUSTRY|2|4
        airfield|Airfield|MILITARY|1|5
        radar_post|Radar Post|DEFENCE|2|2
        aa_battery|Anti-Air Battery|DEFENCE|4|2
        pillbox|Pillbox|DEFENCE|8|1
        bunker|Reinforced Bunker|DEFENCE|3|3
        minefield|Minefield|DEFENCE|10|1
        barbed_wire|Barbed Wire|DEFENCE|30|1
        machine_gun_nest|Machine Gun Nest|DEFENCE|6|1
        artillery_park|Artillery Park|MILITARY|2|4
        tank_depot|Tank Depot|MILITARY|2|4
        motor_pool|Motor Pool|MILITARY|2|3
        barracks_modern|Modern Barracks|MILITARY|3|3
        research_lab|Research Laboratory|RESEARCH|2|3
        wind_tunnel|Wind Tunnel|RESEARCH|1|3
        refinery|Oil Refinery|INDUSTRY|1|4
        oil_derrick|Oil Derrick|RESOURCE|3|2
        water_works|Water Works|INFRASTRUCTURE|1|3
        hospital|Hospital|CIVIC|1|4
        school|School|CIVIC|2|3
        radio_mast|Radio Mast|INFRASTRUCTURE|1|1
        telephone_exchange|Telephone Exchange|INFRASTRUCTURE|1|2
        supermarket|Supermarket|CIVIC|2|3
        rocket_pad|Rocket Pad|MILITARY|1|4
        missile_silo|Missile Silo|DEFENCE|2|3
        command_post|Command Post|MILITARY|1|3
        helipad|Helipad|MILITARY|2|3
        naval_dock|Naval Dock|MILITARY|1|5
        primary_school_public|Public Primary School|RESEARCH|3|3
        primary_school_private|Private Primary School|RESEARCH|2|3
        secondary_school_public|Public Secondary School|RESEARCH|2|4
        secondary_school_private|Private Secondary School|RESEARCH|2|4
        grammar_school_modern|Grammar School|RESEARCH|2|3
        college_modern|College|RESEARCH|2|4
        polytechnic|Polytechnic|RESEARCH|1|4
        teacher_college|Teacher Training College|RESEARCH|1|3
        night_school|Night School|RESEARCH|2|2
        nursery|Nursery|RESEARCH|3|2
        concrete_works|Concrete Works|INDUSTRY|2|3
        cement_kiln|Cement Kiln|INDUSTRY|2|3
        rolling_mill|Rolling Mill|INDUSTRY|2|4
        blast_furnace|Blast Furnace|INDUSTRY|1|4
        coking_plant|Coking Plant|INDUSTRY|1|4
        foundry_modern|Iron Foundry|INDUSTRY|2|3
        machine_shop|Machine Shop|INDUSTRY|3|3
        tool_and_die|Tool and Die Works|INDUSTRY|2|3
        bearing_plant|Bearing Plant|INDUSTRY|2|3
        gear_works|Gear Works|INDUSTRY|2|3
        boiler_works|Boiler Works|INDUSTRY|1|4
        turbine_hall|Turbine Hall|INDUSTRY|1|4
        generator_hall|Generator Hall|INDUSTRY|1|4
        transformer_yard|Transformer Yard|INFRASTRUCTURE|2|3
        substation|Substation|INFRASTRUCTURE|3|2
        pylon_line|Pylon Line|INFRASTRUCTURE|20|1
        gasworks|Gasworks|INDUSTRY|1|4
        coal_gas_holder|Gas Holder|INDUSTRY|2|3
        chemical_plant|Chemical Plant|INDUSTRY|1|4
        fertiliser_plant|Fertiliser Plant|INDUSTRY|1|4
        explosives_plant|Explosives Plant|INDUSTRY|1|4
        rubber_works|Rubber Works|INDUSTRY|1|3
        plastics_plant|Plastics Plant|INDUSTRY|1|3
        glass_float_plant|Float Glass Plant|INDUSTRY|1|4
        paper_machine|Paper Machine Hall|INDUSTRY|1|4
        textile_mill|Textile Mill|INDUSTRY|2|4
        garment_factory|Garment Factory|INDUSTRY|2|3
        shoe_factory|Shoe Factory|INDUSTRY|2|3
        cannery|Cannery|INDUSTRY|2|3
        flour_mill_modern|Roller Flour Mill|INDUSTRY|2|3
        brewery_modern|Industrial Brewery|INDUSTRY|1|3
        dairy_plant|Dairy Plant|INDUSTRY|2|3
        meat_packing|Meat Packing Plant|INDUSTRY|1|4
        cold_store|Cold Store|INDUSTRY|2|3
        grain_elevator|Grain Elevator|AGRICULTURE|2|3
        silo_battery|Silo Battery|AGRICULTURE|3|2
        tractor_shed|Tractor Shed|AGRICULTURE|3|2
        combine_barn|Combine Barn|AGRICULTURE|2|3
        irrigation_pump|Irrigation Pump House|AGRICULTURE|3|2
        centre_pivot|Centre Pivot Field|AGRICULTURE|3|4
        greenhouse_range|Greenhouse Range|AGRICULTURE|3|3
        poultry_house|Broiler House|AGRICULTURE|3|3
        feedlot|Feedlot|AGRICULTURE|2|4
        milking_parlour|Milking Parlour|AGRICULTURE|2|3
        seed_store|Seed Store|AGRICULTURE|2|2
        agronomy_station|Agronomy Station|RESEARCH|1|3
        crop_spraying_strip|Crop Spraying Strip|AGRICULTURE|1|4
        drainage_scheme|Drainage Scheme|AGRICULTURE|3|3
        shelter_belt|Shelter Belt|AGRICULTURE|4|3
        orchard_modern|Commercial Orchard|AGRICULTURE|2|4
        vineyard_modern|Commercial Vineyard|AGRICULTURE|2|4
        fish_farm|Fish Farm|AGRICULTURE|2|3
        abattoir|Abattoir|AGRICULTURE|1|3
        wool_shed|Wool Shed|AGRICULTURE|2|3
        department_store|Department Store|TRADE|1|4
        high_street_shops|High Street Shops|TRADE|3|3
        arcade|Shopping Arcade|TRADE|1|3
        wholesale_market|Wholesale Market|TRADE|1|4
        commodity_exchange|Commodity Exchange|TRADE|1|3
        stock_exchange|Stock Exchange|TRADE|1|3
        bank_branch|Bank Branch|TRADE|3|2
        central_bank|Central Bank|TRADE|1|4
        insurance_house|Insurance House|TRADE|1|3
        customs_house|Customs House|TRADE|1|3
        container_terminal|Container Terminal|TRADE|1|5
        freight_depot|Freight Depot|TRADE|2|4
        warehouse_modern|Distribution Warehouse|TRADE|3|4
        petrol_station|Petrol Station|TRADE|4|2
        hotel|Hotel|TRADE|2|3
        cinema|Cinema|CIVIC|2|3
        theatre_modern|Theatre|CIVIC|1|3
        stadium|Stadium|CIVIC|1|5
        swimming_baths|Swimming Baths|CIVIC|1|3
        public_library|Public Library|CIVIC|2|3
        museum|Museum|CIVIC|1|4
        art_gallery|Art Gallery|CIVIC|1|3
        concert_hall|Concert Hall|CIVIC|1|4
        town_hall_modern|Civic Hall|CIVIC|1|4
        courthouse_modern|Courthouse|CIVIC|1|3
        police_station|Police Station|CIVIC|2|3
        fire_station|Fire Station|CIVIC|2|3
        ambulance_station|Ambulance Station|CIVIC|2|2
        post_office|Post Office|CIVIC|2|2
        telephone_kiosk|Telephone Kiosk|INFRASTRUCTURE|6|1
        clinic|Clinic|CIVIC|3|2
        maternity_home|Maternity Home|CIVIC|1|3
        sanatorium|Sanatorium|CIVIC|1|4
        pharmacy|Pharmacy|CIVIC|3|2
        crematorium|Crematorium|CIVIC|1|3
        cemetery_modern|Cemetery|CIVIC|1|4
        allotments|Allotments|AGRICULTURE|3|3
        public_park|Public Park|CIVIC|2|4
        bandstand|Bandstand|CIVIC|2|1
        terraced_housing|Terraced Housing|HOUSING|5|3
        semi_detached|Semi-Detached Houses|HOUSING|4|2
        tower_block|Tower Block|HOUSING|3|3
        prefab_estate|Prefab Estate|HOUSING|3|3
        workers_tenement|Workers Tenement|HOUSING|4|3
        company_town_row|Company Town Row|HOUSING|3|3
        officers_quarters|Officers Quarters|HOUSING|2|3
        barrack_block|Barrack Block|HOUSING|3|3
        student_halls|Student Halls|HOUSING|2|3
        almshouse_modern|Sheltered Housing|HOUSING|2|3
        tram_depot|Tram Depot|INFRASTRUCTURE|1|4
        bus_garage|Bus Garage|INFRASTRUCTURE|2|4
        railway_station|Railway Station|INFRASTRUCTURE|1|4
        marshalling_yard|Marshalling Yard|INFRASTRUCTURE|1|5
        engine_shed|Engine Shed|INFRASTRUCTURE|2|4
        signal_box|Signal Box|INFRASTRUCTURE|3|1
        level_crossing|Level Crossing|INFRASTRUCTURE|4|1
        viaduct|Viaduct|INFRASTRUCTURE|2|4
        road_bridge|Road Bridge|INFRASTRUCTURE|3|3
        motorway|Motorway|INFRASTRUCTURE|12|2
        ring_road|Ring Road|INFRASTRUCTURE|8|2
        underpass|Underpass|INFRASTRUCTURE|4|2
        multi_storey_car_park|Multi-Storey Car Park|INFRASTRUCTURE|2|3
        canal_modern|Ship Canal|INFRASTRUCTURE|2|4
        dry_dock|Dry Dock|INFRASTRUCTURE|1|5
        harbour_crane_modern|Harbour Crane|INFRASTRUCTURE|3|2
        airport_terminal|Airport Terminal|INFRASTRUCTURE|1|5
        control_tower|Control Tower|INFRASTRUCTURE|1|2
        hangar|Hangar|INFRASTRUCTURE|2|4
        fuel_farm|Fuel Farm|INFRASTRUCTURE|2|3
        pumping_station|Pumping Station|INFRASTRUCTURE|2|3
        sewage_works|Sewage Works|INFRASTRUCTURE|1|4
        reservoir_modern|Reservoir|INFRASTRUCTURE|1|5
        water_tower|Water Tower|INFRASTRUCTURE|3|2
        incinerator|Incinerator|INFRASTRUCTURE|1|4
        landfill|Landfill|INFRASTRUCTURE|1|5
        telegraph_office|Telegraph Office|INFRASTRUCTURE|2|2
        broadcast_house|Broadcast House|INFRASTRUCTURE|1|3
        relay_mast|Relay Mast|INFRASTRUCTURE|3|1
        pillbox_line|Pillbox Line|FORTIFICATION|12|1
        dragon_teeth|Dragons Teeth|FORTIFICATION|16|1
        tank_ditch|Anti-Tank Ditch|FORTIFICATION|12|1
        concrete_bunker|Concrete Bunker|FORTIFICATION|4|3
        command_bunker|Command Bunker|FORTIFICATION|1|3
        observation_post|Observation Post|FORTIFICATION|4|1
        gun_emplacement|Gun Emplacement|FORTIFICATION|4|2
        coastal_battery|Coastal Battery|FORTIFICATION|2|3
        turret_cupola|Armoured Cupola|FORTIFICATION|4|1
        maginot_block|Fortress Block|FORTIFICATION|2|4
        artillery_casemate|Artillery Casemate|FORTIFICATION|3|3
        ammunition_bunker|Ammunition Bunker|FORTIFICATION|3|2
        blast_wall|Blast Wall|FORTIFICATION|18|1
        sandbag_emplacement|Sandbag Emplacement|FORTIFICATION|14|1
        wire_entanglement|Wire Entanglement|FORTIFICATION|18|1
        minefield_marked|Marked Minefield|FORTIFICATION|10|2
        trench_line|Trench Line|FORTIFICATION|14|2
        communication_trench|Communication Trench|FORTIFICATION|10|1
        dugout|Dugout|FORTIFICATION|8|1
        revetment|Revetment|FORTIFICATION|12|1
        air_raid_shelter|Air Raid Shelter|FORTIFICATION|4|2
        decontamination_post|Decontamination Post|FORTIFICATION|2|2
        searchlight_battery|Searchlight Battery|DEFENCE|4|2
        sound_locator|Sound Locator|DEFENCE|3|2
        barrage_balloon|Barrage Balloon|DEFENCE|5|2
        radar_early_warning|Early Warning Radar|DEFENCE|2|3
        sam_site|SAM Site|DEFENCE|3|3
        aaa_battery|AAA Battery|DEFENCE|5|2
        mortar_pit|Mortar Pit|DEFENCE|6|1
        atgm_post|ATGM Post|DEFENCE|5|1
        guard_tower_modern|Guard Tower|DEFENCE|5|1
        checkpoint|Checkpoint|DEFENCE|4|2
        officer_school|Officer School|MILITARY|1|4
        staff_college|Staff College|MILITARY|1|4
        recruit_depot|Recruit Depot|MILITARY|2|3
        training_ground|Training Ground|MILITARY|2|5
        firing_range|Firing Range|MILITARY|2|4
        driving_school|Armour Driving School|MILITARY|1|4
        signals_school|Signals School|MILITARY|1|3
        engineer_depot|Engineer Depot|MILITARY|2|3
        bridging_park|Bridging Park|MILITARY|1|4
        field_workshop|Field Workshop|MILITARY|2|3
        ordnance_depot|Ordnance Depot|MILITARY|2|4
        quartermaster_store|Quartermaster Store|MILITARY|2|3
        field_hospital_modern|Field Hospital|MILITARY|2|3
        veterinary_post|Veterinary Post|MILITARY|1|2
        military_prison|Military Prison|MILITARY|1|3
        intelligence_office|Intelligence Office|MILITARY|1|3
        cipher_room|Cipher Room|RESEARCH|1|2
        proving_ground|Proving Ground|RESEARCH|1|5
        ballistics_lab|Ballistics Laboratory|RESEARCH|1|3
        metallurgy_lab|Metallurgy Laboratory|RESEARCH|1|3
        chemistry_lab|Chemistry Laboratory|RESEARCH|2|3
        physics_lab|Physics Laboratory|RESEARCH|2|3
        electronics_lab|Electronics Laboratory|RESEARCH|2|3
        aeronautics_lab|Aeronautics Laboratory|RESEARCH|1|4
        rocketry_lab|Rocketry Laboratory|RESEARCH|1|4
        nuclear_pile|Nuclear Pile|RESEARCH|1|4
        computing_centre|Computing Centre|RESEARCH|1|4
        university_modern|University|RESEARCH|1|5
        technical_college|Technical College|RESEARCH|2|4
        observatory_modern|Observatory|RESEARCH|1|3
        weather_station|Weather Station|RESEARCH|2|2
        oil_well|Oil Well|RESOURCE|4|2
        gas_field|Gas Field|RESOURCE|2|3
        open_cast_mine|Open Cast Mine|RESOURCE|2|5
        deep_shaft_mine|Deep Shaft Mine|RESOURCE|2|4
        bauxite_pit|Bauxite Pit|RESOURCE|2|3
        nickel_mine|Nickel Mine|RESOURCE|2|3
        uranium_mine|Uranium Mine|RESOURCE|1|3
        potash_mine|Potash Mine|RESOURCE|2|3
        phosphate_works|Phosphate Works|RESOURCE|2|3
        gravel_pit|Gravel Pit|RESOURCE|3|3
        timber_plantation|Timber Plantation|RESOURCE|3|4
        pulp_yard|Pulp Yard|RESOURCE|2|4
        desalination_early|Desalination Plant|RESOURCE|1|4
        hydro_dam|Hydroelectric Dam|INDUSTRY|1|5
        wind_farm_early|Wind Farm|INDUSTRY|2|4
        solar_farm_early|Solar Farm|INDUSTRY|2|4
        nuclear_station|Nuclear Power Station|INDUSTRY|1|5
    """.trimIndent()

    /**
     * Futuristic, tiers 156–249: levels 313 to 499.
     *
     * The longest era and the one with the most room to invent in. The rule followed here is that
     * every entry has to be a *place*, not an effect — "Gravitic Foundry" is a building somebody
     * could draw in pencil; "Improved Damage" is a stat masquerading as one.
     */
    private val FUTURISTIC = """
        fusion_plant|Fusion Plant|INDUSTRY|1|4
        arcology|Arcology|HOUSING|2|5
        nanoforge|Nanoforge|INDUSTRY|2|3
        drone_bay|Drone Bay|MILITARY|3|3
        rail_battery|Railgun Battery|DEFENCE|3|3
        shield_pylon|Shield Pylon|DEFENCE|4|2
        plasma_turret|Plasma Turret|DEFENCE|6|1
        laser_lattice|Laser Lattice|DEFENCE|4|2
        grav_road|Gravitic Roadway|INFRASTRUCTURE|28|1
        maglev_line|Maglev Line|INFRASTRUCTURE|8|2
        orbital_lift|Orbital Lift|INFRASTRUCTURE|1|5
        launch_cradle|Launch Cradle|MILITARY|1|5
        mech_bay|Mech Bay|MILITARY|2|4
        exo_barracks|Exosuit Barracks|MILITARY|3|3
        cloning_vats|Cloning Vats|MILITARY|1|4
        neural_academy|Neural Academy|RESEARCH|1|4
        quantum_lab|Quantum Laboratory|RESEARCH|2|3
        ai_core|AI Core|RESEARCH|1|4
        fabricator|Matter Fabricator|INDUSTRY|2|3
        recycler|Molecular Recycler|INDUSTRY|2|3
        hydroponics|Hydroponics Tower|RESOURCE|3|3
        algae_vat|Algae Vat|RESOURCE|4|2
        fusion_farm|Fusion Farm|RESOURCE|2|4
        helium_rig|Helium-3 Rig|RESOURCE|2|3
        antimatter_trap|Antimatter Trap|RESOURCE|1|3
        cryo_store|Cryogenic Store|INDUSTRY|2|2
        sensor_spire|Sensor Spire|DEFENCE|2|2
        jammer_post|Jammer Post|DEFENCE|3|1
        point_defence|Point Defence Node|DEFENCE|8|1
        tesla_pylon|Tesla Pylon|DEFENCE|4|1
        emp_mine|EMP Mine|DEFENCE|12|1
        hardlight_wall|Hardlight Wall|DEFENCE|32|1
        aegis_dome|Aegis Dome|DEFENCE|1|5
        missile_grid|Missile Grid|DEFENCE|3|3
        void_anchor|Void Anchor|DEFENCE|2|3
        gene_clinic|Gene Clinic|CIVIC|2|3
        mind_archive|Mind Archive|CIVIC|1|4
        habitat_ring|Habitat Ring|HOUSING|3|5
        capsule_stack|Capsule Stack|HOUSING|5|2
        terrace_block|Terrace Block|HOUSING|4|3
        sky_market|Sky Market|CIVIC|2|4
        holo_theatre|Holo Theatre|CIVIC|1|3
        transit_hub|Transit Hub|INFRASTRUCTURE|2|4
        power_relay|Power Relay|INFRASTRUCTURE|6|1
        data_spire|Data Spire|INFRASTRUCTURE|2|3
        weather_mast|Weather Control Mast|INFRASTRUCTURE|1|3
        desal_stack|Desalination Stack|INFRASTRUCTURE|2|3
        atmo_scrubber|Atmospheric Scrubber|INFRASTRUCTURE|2|3
        gravitic_foundry|Gravitic Foundry|INDUSTRY|1|4
        plasma_smelter|Plasma Smelter|INDUSTRY|2|3
        nanite_hive|Nanite Hive|INDUSTRY|2|3
        drone_factory|Drone Factory|INDUSTRY|2|4
        warframe_works|Warframe Works|INDUSTRY|1|4
        railmass_plant|Rail Mass Plant|INDUSTRY|1|4
        singularity_tap|Singularity Tap|INDUSTRY|1|4
        exotic_refinery|Exotic Matter Refinery|INDUSTRY|1|4
        orbital_yard|Orbital Shipyard|MILITARY|1|6
        strike_hangar|Strike Hangar|MILITARY|2|4
        interceptor_pad|Interceptor Pad|MILITARY|3|3
        siege_walker_bay|Siege Walker Bay|MILITARY|1|5
        swarm_silo|Swarm Silo|MILITARY|2|3
        kinetic_battery|Kinetic Strike Battery|MILITARY|1|4
        orbital_gun|Orbital Gun Emplacement|DEFENCE|1|4
        stealth_field|Stealth Field Generator|DEFENCE|2|3
        repair_nexus|Repair Nexus|MILITARY|2|3
        supply_matrix|Supply Matrix|MILITARY|1|4
        command_lattice|Command Lattice|MILITARY|1|4
        tactical_net|Tactical Net Hub|MILITARY|1|3
        officer_academy|Officer Academy|MILITARY|1|4
        psi_chamber|Psionics Chamber|RESEARCH|1|3
        temporal_lab|Temporal Laboratory|RESEARCH|1|4
        xeno_archive|Xenology Archive|RESEARCH|1|3
        materials_ring|Materials Ring|RESEARCH|1|4
        fusion_testbed|Fusion Testbed|RESEARCH|1|3
        rail_proving|Rail Proving Ground|RESEARCH|1|5
        drone_proving|Drone Proving Ground|RESEARCH|1|4
        beam_proving|Beam Proving Ground|RESEARCH|1|4
        deep_core_mine|Deep Core Mine|RESOURCE|2|4
        asteroid_dock|Asteroid Dock|RESOURCE|1|5
        geothermal_tap|Geothermal Tap|RESOURCE|2|3
        solar_array|Solar Array|RESOURCE|4|3
        fusion_battery|Fusion Battery Bank|INDUSTRY|3|2
        grav_well|Gravity Well Generator|DEFENCE|1|4
        phase_gate|Phase Gate|INFRASTRUCTURE|1|3
        relay_beacon|Relay Beacon|INFRASTRUCTURE|4|1
        civic_ai|Civic AI Annex|CIVIC|1|3
        memorial_spire|Memorial Spire|CIVIC|1|2
        garden_dome|Garden Dome|CIVIC|2|4
        education_ring|Education Ring|CIVIC|1|4
        medical_ring|Medical Ring|CIVIC|1|4
        arcology_crown|Arcology Crown|HOUSING|1|6
        world_engine|World Engine|CORE|1|6
        creche_pod|Creche Pod|RESEARCH|4|2
        primary_lattice|Primary Learning Lattice|RESEARCH|3|3
        secondary_lattice|Secondary Learning Lattice|RESEARCH|3|4
        neural_college|Neural College|RESEARCH|2|4
        open_university|Open University|RESEARCH|1|5
        apprentice_sim|Apprenticeship Simulator|RESEARCH|2|3
        polymath_institute|Polymath Institute|RESEARCH|1|4
        arc_smelter|Arc Smelter|INDUSTRY|2|4
        carbon_loom|Carbon Loom|INDUSTRY|2|3
        graphene_mill|Graphene Mill|INDUSTRY|2|3
        metamaterial_press|Metamaterial Press|INDUSTRY|1|4
        superconductor_line|Superconductor Line|INDUSTRY|1|4
        photonics_fab|Photonics Fab|INDUSTRY|2|3
        wafer_fab|Wafer Fab|INDUSTRY|1|5
        printed_organ_lab|Printed Organ Lab|INDUSTRY|1|3
        protein_vats|Protein Vats|INDUSTRY|3|3
        synthfood_plant|Synthetic Food Plant|INDUSTRY|2|4
        water_reclaimer|Water Reclaimer|INFRASTRUCTURE|3|3
        air_processor|Air Processor|INFRASTRUCTURE|2|4
        heat_sink_array|Heat Sink Array|INFRASTRUCTURE|3|3
        fusion_tokamak|Fusion Tokamak|INDUSTRY|1|5
        stellarator|Stellarator|INDUSTRY|1|5
        antimatter_ring|Antimatter Ring|INDUSTRY|1|5
        zero_point_tap|Zero Point Tap|INDUSTRY|1|4
        beamed_power_rectenna|Rectenna Field|INFRASTRUCTURE|2|5
        orbital_mirror_control|Orbital Mirror Control|INFRASTRUCTURE|1|3
        gravity_plating|Gravity Plating|INFRASTRUCTURE|6|2
        inertial_damper|Inertial Damper|INFRASTRUCTURE|3|2
        vacuum_tube_transit|Vacuum Tube Transit|INFRASTRUCTURE|4|3
        skyhook_anchor|Skyhook Anchor|INFRASTRUCTURE|1|5
        launch_loop|Launch Loop|INFRASTRUCTURE|1|6
        drone_corridor|Drone Corridor|INFRASTRUCTURE|6|2
        autonomous_freight_hub|Autonomous Freight Hub|TRADE|2|4
        matter_market|Matter Market|TRADE|2|3
        pattern_exchange|Pattern Exchange|TRADE|1|3
        credit_lattice|Credit Lattice|TRADE|1|3
        commons_vault|Commons Vault|TRADE|1|3
        replicator_shop|Replicator Shop|TRADE|4|2
        bazaar_dome|Bazaar Dome|TRADE|1|4
        orbital_customs|Orbital Customs|TRADE|1|4
        void_wharf|Void Wharf|TRADE|1|5
        cargo_catapult|Cargo Catapult|TRADE|1|4
        vertical_farm|Vertical Farm|AGRICULTURE|3|4
        aeroponic_stack|Aeroponic Stack|AGRICULTURE|4|3
        mycoprotein_vat|Mycoprotein Vat|AGRICULTURE|3|3
        insect_farm|Insect Farm|AGRICULTURE|3|2
        kelp_raft|Kelp Raft|AGRICULTURE|3|4
        coral_farm|Coral Farm|AGRICULTURE|2|3
        cellular_ranch|Cellular Ranch|AGRICULTURE|2|4
        seed_vault|Seed Vault|AGRICULTURE|1|3
        gene_orchard|Gene Orchard|AGRICULTURE|2|4
        pollinator_hive|Robotic Pollinator Hive|AGRICULTURE|3|2
        soil_printer|Soil Printer|AGRICULTURE|2|3
        biodome|Biodome|AGRICULTURE|2|5
        terraform_plot|Terraforming Plot|AGRICULTURE|2|5
        atmosphere_farm|Atmosphere Farm|AGRICULTURE|2|4
        rain_seeder|Rain Seeder|AGRICULTURE|2|2
        arcology_spire|Arcology Spire|HOUSING|2|6
        habitat_cluster|Habitat Cluster|HOUSING|3|4
        pod_hotel|Pod Hotel|HOUSING|4|3
        gravity_ring_housing|Gravity Ring Housing|HOUSING|2|5
        cryo_dormitory|Cryo Dormitory|HOUSING|2|3
        uplift_quarter|Uplift Quarter|HOUSING|2|4
        synthetic_quarter|Synthetic Quarter|HOUSING|2|4
        garden_terrace|Garden Terrace|HOUSING|3|3
        floating_district|Floating District|HOUSING|2|5
        subsurface_warren|Subsurface Warren|HOUSING|3|4
        civic_lattice|Civic Lattice|CIVIC|1|4
        assembly_forum|Assembly Forum|CIVIC|1|4
        arbitration_court|Arbitration Court|CIVIC|1|3
        census_engine|Census Engine|CIVIC|1|3
        welfare_node|Welfare Node|CIVIC|2|3
        longevity_clinic|Longevity Clinic|CIVIC|1|3
        neural_clinic|Neural Clinic|CIVIC|2|3
        rehabilitation_pod|Rehabilitation Pod|CIVIC|3|2
        memorial_archive|Memorial Archive|CIVIC|1|3
        sensorium|Sensorium|CIVIC|1|4
        zero_g_arena|Zero-G Arena|CIVIC|1|5
        culture_vault|Culture Vault|CIVIC|1|3
        simulation_park|Simulation Park|CIVIC|1|4
        meditation_spire|Meditation Spire|CIVIC|2|2
        uplift_academy|Uplift Academy|RESEARCH|1|4
        cognition_lab|Cognition Laboratory|RESEARCH|2|3
        genome_forge|Genome Forge|RESEARCH|1|4
        nanotech_institute|Nanotechnology Institute|RESEARCH|1|4
        fusion_institute|Fusion Institute|RESEARCH|1|4
        gravitics_institute|Gravitics Institute|RESEARCH|1|4
        exotic_matter_lab|Exotic Matter Laboratory|RESEARCH|1|4
        temporal_observatory|Temporal Observatory|RESEARCH|1|4
        deep_field_array|Deep Field Array|RESEARCH|1|5
        neutrino_detector|Neutrino Detector|RESEARCH|1|4
        particle_ring|Particle Ring|RESEARCH|1|6
        simulation_cluster|Simulation Cluster|RESEARCH|2|4
        machine_mind_vault|Machine Mind Vault|RESEARCH|1|4
        xeno_quarantine|Xeno Quarantine|RESEARCH|1|4
        doctrine_forge|Doctrine Forge|RESEARCH|1|3
        rail_curtain|Rail Curtain|FORTIFICATION|10|2
        hardlight_bastion|Hardlight Bastion|FORTIFICATION|4|3
        phase_wall|Phase Wall|FORTIFICATION|20|1
        kinetic_barrier|Kinetic Barrier|FORTIFICATION|16|1
        ablative_berm|Ablative Berm|FORTIFICATION|14|1
        shield_dome_small|Shield Dome|FORTIFICATION|2|4
        deflector_pylon|Deflector Pylon|FORTIFICATION|5|2
        stasis_perimeter|Stasis Perimeter|FORTIFICATION|8|2
        gravity_moat|Gravity Moat|FORTIFICATION|8|2
        null_field_post|Null Field Post|FORTIFICATION|4|2
        armoured_arcology_base|Armoured Arcology Base|FORTIFICATION|1|5
        blast_cradle|Blast Cradle|FORTIFICATION|3|3
        redoubt_lattice|Redoubt Lattice|FORTIFICATION|4|3
        citadel_core|Citadel Core|FORTIFICATION|1|5
        bunker_complex|Deep Bunker Complex|FORTIFICATION|2|4
        emp_hardened_vault|EMP-Hardened Vault|FORTIFICATION|2|3
        drone_nest|Drone Nest|DEFENCE|5|2
        interceptor_lattice|Interceptor Lattice|DEFENCE|4|3
        railgun_turret|Railgun Turret|DEFENCE|5|2
        particle_turret|Particle Turret|DEFENCE|5|2
        graviton_turret|Graviton Turret|DEFENCE|4|2
        singularity_mine|Singularity Mine|DEFENCE|8|1
        phase_mine|Phase Mine|DEFENCE|10|1
        swarm_dispenser|Swarm Dispenser|DEFENCE|4|2
        sentinel_frame|Sentinel Frame|DEFENCE|4|2
        aegis_node|Aegis Node|DEFENCE|3|3
        orbital_uplink_defence|Orbital Fire Uplink|DEFENCE|1|3
        counter_battery_net|Counter-Battery Net|DEFENCE|2|3
        stealth_canopy|Stealth Canopy|DEFENCE|2|4
        decoy_field|Decoy Field|DEFENCE|6|2
        jammer_lattice|Jammer Lattice|DEFENCE|4|2
        exo_academy|Exosuit Academy|MILITARY|1|4
        mech_foundry|Mech Foundry|MILITARY|1|5
        walker_bay|Walker Bay|MILITARY|2|5
        drone_command|Drone Command|MILITARY|1|4
        swarm_hatchery|Swarm Hatchery|MILITARY|2|4
        clone_barracks|Clone Barracks|MILITARY|2|4
        neural_drill_hall|Neural Drill Hall|MILITARY|2|3
        tactical_simulator|Tactical Simulator|MILITARY|2|3
        orbital_drop_pad|Orbital Drop Pad|MILITARY|2|4
        boarding_dock|Boarding Dock|MILITARY|1|5
        siege_engine_bay|Siege Engine Bay|MILITARY|1|5
        logistics_lattice|Logistics Lattice|MILITARY|1|4
        field_fabricator|Field Fabricator|MILITARY|2|3
        medbay_complex|Medbay Complex|MILITARY|2|3
        veteran_vault|Veteran Vault|MILITARY|1|3
        war_council_ring|War Council Ring|MILITARY|1|4
        asteroid_refinery|Asteroid Refinery|RESOURCE|1|5
        regolith_harvester|Regolith Harvester|RESOURCE|2|4
        volatiles_still|Volatiles Still|RESOURCE|2|3
        helium3_scoop|Helium-3 Scoop|RESOURCE|2|4
        rare_earth_leach|Rare Earth Leach|RESOURCE|2|3
        isotope_separator|Isotope Separator|RESOURCE|1|4
        magma_tap|Magma Tap|RESOURCE|1|4
        ocean_thermal_plant|Ocean Thermal Plant|RESOURCE|1|5
        atmospheric_harvester|Atmospheric Harvester|RESOURCE|2|4
        dark_matter_sieve|Dark Matter Sieve|RESOURCE|1|4
        quantum_relay|Quantum Relay|INFRASTRUCTURE|2|3
        entanglement_exchange|Entanglement Exchange|INFRASTRUCTURE|1|3
        mesh_spire|Mesh Spire|INFRASTRUCTURE|3|3
        data_vault|Data Vault|INFRASTRUCTURE|2|3
        civic_ai_core|Civic AI Core|INFRASTRUCTURE|1|4
        weather_lattice|Weather Lattice|INFRASTRUCTURE|1|4
        seismic_damper|Seismic Damper|INFRASTRUCTURE|3|3
        waste_annihilator|Waste Annihilator|INFRASTRUCTURE|2|3
        carbon_scrubber_tower|Carbon Scrubber Tower|INFRASTRUCTURE|3|3
        magnetosphere_pylon|Magnetosphere Pylon|INFRASTRUCTURE|2|4
    """.trimIndent()

    // -- Parsing and placement ------------------------------------------------

    /** Tier ranges, derived so a change to the era boundaries carries through here. */
    private val medievalTiers = Era.unlockTierOf(Era.Age.MEDIEVAL.first)..Era.unlockTierOf(Era.Age.MEDIEVAL.last)
    private val modernTiers = Era.unlockTierOf(Era.Age.MODERN.first)..Era.unlockTierOf(Era.Age.MODERN.last)
    private val futureTiers = Era.unlockTierOf(Era.Age.FUTURISTIC.first)..Era.unlockTierOf(Era.Age.FUTURISTIC.last)

    /**
     * Spreads an era's authored list evenly across the tiers that era owns.
     *
     * Even spacing rather than one-per-tier, because the lists are not the same length as their
     * tier ranges and never will be — an era with more buildings than tiers unlocks two at a time
     * here and there, and one with fewer leaves gaps. Both are fine; a crash because the author
     * added a building is not.
     */
    private fun parse(block: String, age: Era.Age, tiers: IntRange): List<BuildingType> {
        val lines = block.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val span = (tiers.last - tiers.first + 1).coerceAtLeast(1)

        return lines.mapIndexed { index, line ->
            val parts = line.split('|')
            require(parts.size == 5) { "malformed building line: $line" }
            val offset = if (lines.size <= 1) 0 else (index.toLong() * (span - 1) / (lines.size - 1)).toInt()
            BuildingType(
                id = parts[0],
                name = parts[1],
                category = Category.valueOf(parts[2]),
                age = age,
                tier = (tiers.first + offset).coerceIn(tiers),
                maxBase = parts[3].toInt(),
                footprint = parts[4].toInt(),
            )
        }
    }

    val ALL: List<BuildingType> by lazy {
        val medieval = parse(MEDIEVAL, Era.Age.MEDIEVAL, medievalTiers).toMutableList()

        // The opening position is fixed by the design and must not drift when the list is edited:
        // the town hall, one builders' hut and the training camps exist at level 1, and weapons
        // research, homes, roads and the small industry arrive right after level 2.
        fun pin(id: String, tier: Int) {
            val at = medieval.indexOfFirst { it.id == id }
            if (at >= 0) medieval[at] = medieval[at].copy(tier = tier)
        }
        pin("town_hall", 0)
        pin("builders_hut", 0)
        pin("training_camp", 0)
        pin("weapons_research", 1)
        pin("civilian_hut", 1)
        pin("dirt_road", 1)
        pin("wheat_farm", 1)

        (medieval + parse(MODERN, Era.Age.MODERN, modernTiers) +
            parse(FUTURISTIC, Era.Age.FUTURISTIC, futureTiers))
            .sortedWith(compareBy({ it.tier }, { it.category.ordinal }, { it.id }))
    }

    private val byId: Map<String, BuildingType> by lazy { ALL.associateBy { it.id } }

    fun byId(id: String): BuildingType? = byId[id]

    /** Everything a town hall at [level] is allowed to place. */
    fun unlockedAt(level: Int): List<BuildingType> {
        val tier = Era.unlockTierOf(level)
        return ALL.filter { it.tier <= tier }
    }

    /** What becomes available on reaching [level], for the "new at this level" notice. */
    fun newAt(level: Int): List<BuildingType> {
        if (level < Era.MIN_LEVEL) return emptyList()
        val tier = Era.unlockTierOf(level)
        // Only levels that actually start a tier introduce anything: odd levels, by construction.
        if (level != tier * 2 + 1) return emptyList()
        return ALL.filter { it.tier == tier }
    }

    val TOWN_HALL: BuildingType by lazy { byId("town_hall") ?: ALL.first() }
    val BUILDERS_HUT: BuildingType by lazy { byId("builders_hut") ?: ALL.first() }
    val TRAINING_CAMP: BuildingType by lazy { byId("training_camp") ?: ALL.first() }
}
