package com.prism.launcher.minigames

/**
 * What the stick figures fight with.
 *
 * ## Usable, not decorative
 *
 * Every entry here is reachable by a soldier in a battle: [Battle] reads [Weapon.damage],
 * [Weapon.range], [Weapon.cooldownTicks] and [Weapon.splash] and nothing else, so adding a line to
 * one of the blocks below adds a weapon that genuinely behaves differently on the paper. A catalogue
 * of names that all resolve to the same numbers would be a list, not a game.
 *
 * ## Why stats come from class and tier
 *
 * Four hundred-odd weapons cannot be balanced by hand, and pretending otherwise produces a
 * catalogue where the level-380 rifle is worse than the level-20 bow because somebody typed a 7
 * where they meant 70. So a weapon's identity is its NAME and its CLASS — both authored — and its
 * numbers come from [WeaponClass]'s profile scaled by how far through the game it unlocks. The
 * result is monotonic by construction: a later weapon of the same class is always at least as good,
 * and the classes keep their characters — a bow outranges an axe at every level in the game.
 *
 * ## The eras
 *
 * Medieval weapons unlock from level 2 to 250, modern from 251 to 312, futuristic from 313 to 500,
 * which is exactly the split the buildings use. Research is what makes them available: a weapon is
 * *unlocked* by level and *usable* once a Weapons Research building has studied it.
 */
object WeaponCatalog {

    /**
     * What a weapon is, mechanically.
     *
     * @param reach cells of range. One is arm's length; a siege weapon reaches across the map.
     * @param power damage multiplier against the class baseline.
     * @param rate shots per second at tier zero; a dagger is fast and a trebuchet is not.
     * @param splash cells of blast radius. Zero is a single target.
     */
    enum class WeaponClass(
        val label: String,
        val reach: Int,
        val power: Double,
        val rate: Double,
        val splash: Int,
        val antiBuilding: Double,
    ) {
        BLADE("Blade", reach = 1, power = 1.00, rate = 1.30, splash = 0, antiBuilding = 0.85),
        BLUNT("Blunt", reach = 1, power = 1.25, rate = 0.85, splash = 0, antiBuilding = 1.45),
        POLEARM("Polearm", reach = 2, power = 1.15, rate = 0.95, splash = 0, antiBuilding = 0.90),
        THROWN("Thrown", reach = 4, power = 0.80, rate = 0.90, splash = 0, antiBuilding = 0.70),
        BOW("Bow", reach = 7, power = 0.70, rate = 1.10, splash = 0, antiBuilding = 0.55),
        CROSSBOW("Crossbow", reach = 8, power = 1.05, rate = 0.55, splash = 0, antiBuilding = 0.75),
        SIEGE("Siege", reach = 12, power = 2.60, rate = 0.18, splash = 2, antiBuilding = 2.60),
        FIREARM("Firearm", reach = 9, power = 1.15, rate = 1.40, splash = 0, antiBuilding = 0.80),
        AUTOMATIC("Automatic", reach = 8, power = 0.65, rate = 4.20, splash = 0, antiBuilding = 0.65),
        EXPLOSIVE("Explosive", reach = 6, power = 2.10, rate = 0.35, splash = 3, antiBuilding = 2.10),
        ARTILLERY("Artillery", reach = 16, power = 3.00, rate = 0.15, splash = 4, antiBuilding = 3.10),
        ARMOUR("Armoured", reach = 10, power = 2.20, rate = 0.55, splash = 1, antiBuilding = 2.30),
        AIRCRAFT("Aircraft", reach = 13, power = 2.40, rate = 0.45, splash = 2, antiBuilding = 2.40),
        MISSILE("Missile", reach = 20, power = 3.60, rate = 0.10, splash = 4, antiBuilding = 3.60),
        BEAM("Beam", reach = 11, power = 1.60, rate = 2.20, splash = 0, antiBuilding = 1.30),
        PLASMA("Plasma", reach = 10, power = 2.30, rate = 1.10, splash = 2, antiBuilding = 2.00),
        RAIL("Rail", reach = 18, power = 4.00, rate = 0.30, splash = 1, antiBuilding = 3.40),
        DRONE("Drone", reach = 12, power = 1.40, rate = 1.60, splash = 1, antiBuilding = 1.50),
        EXOTIC("Exotic", reach = 14, power = 3.20, rate = 0.60, splash = 3, antiBuilding = 3.00),
        SUPPORT("Support", reach = 6, power = 0.25, rate = 1.00, splash = 0, antiBuilding = 0.10),
        ;

        val isRanged: Boolean get() = reach >= 3

        /**
         * Whether this is a thing that is crewed rather than carried.
         *
         * A tank is not a rifle with more damage, and drawing a stick figure holding one was always
         * the wrong picture. These are the classes that go out as their own vehicles, in their own
         * detachments, and are drawn as tanks, aircraft, launchers and gun batteries.
         */
        val isCrewed: Boolean
            get() = this == ARMOUR || this == AIRCRAFT || this == ARTILLERY ||
                this == MISSILE || this == SIEGE || this == DRONE
    }

    /**
     * The two things a country can do to another country without invading it.
     *
     * @param label what it is called on the button.
     * @param destruction the share of the target's buildings it takes off the map. Both kinds are
     *   total -- a warhead that only flattened half a city was not doing the one thing a player
     *   reaches for a weapon of mass destruction to do. What tells [NUCLEAR] and [ANTIMATTER] apart
     *   is everything else: how long the wreckage takes to clear ([burnHours]) and what it costs.
     * @param burnHours how long the target is left burning and rebuilding afterwards.
     * @param xpCost what it costs to build and deliver one, as a multiple of the target's XP.
     */
    enum class Wmd(
        val label: String,
        val verb: String,
        val destruction: Double,
        val burnHours: Double,
        val xpCostFactor: Double,
    ) {
        NUCLEAR("Nuclear", "Nuke", destruction = 1.0, burnHours = 6.0, xpCostFactor = 0.45),
        ANTIMATTER("Antimatter", "Antimatter drop", destruction = 1.0, burnHours = 18.0, xpCostFactor = 1.1),
    }

    /**
     * One weapon.
     *
     * @param unlockLevel the town hall level at which it can be researched.
     * @param tier 0.0 at the very start of the game, 1.0 at level 500. The single scalar every
     *   derived number is a function of, so "later is stronger" holds across era boundaries too.
     */
    data class Weapon(
        val id: String,
        val name: String,
        val weaponClass: WeaponClass,
        val age: Era.Age,
        val unlockLevel: Int,
    ) {
        val tier: Double get() = (unlockLevel - 1).toDouble() / (Era.MAX_LEVEL - 1)

        /**
         * Damage per hit.
         *
         * Growth is on a curve rather than a straight line: a level-2 spear and a level-500 railgun
         * should not be within an order of magnitude of each other, and a linear ramp over five
         * hundred levels either makes the early game unplayable or the late game pointless.
         */
        val damage: Int
            get() {
                val growth = 1.0 + 55.0 * tier * tier + 12.0 * tier
                return (9.0 * weaponClass.power * growth).toInt().coerceAtLeast(1)
            }

        /** Extra damage against buildings, which is what most of an attack actually hits. */
        val buildingDamage: Int get() = (damage * weaponClass.antiBuilding).toInt().coerceAtLeast(1)

        /** Cells. Grows slightly within a class so a later bow does outrange an earlier one. */
        val range: Int get() = weaponClass.reach + (tier * 4).toInt()

        val splash: Int get() = weaponClass.splash

        /**
         * Which kind of thing-you-do-not-use-lightly this is, if it is one.
         *
         * Read off the name for the same reason [isRotaryWing] is: the catalogue is a table of
         * names and classes, and "Thermonuclear Warhead" already says what it is. A weapon with a
         * kind here is not an ordinary battlefield weapon -- it is not issued to soldiers and does
         * not go out in a detachment. It is fired at a country, once, from the map.
         */
        val wmd: Wmd?
            get() = when {
                name.contains("Antimatter", ignoreCase = true) -> Wmd.ANTIMATTER
                name.contains("Nuclear", ignoreCase = true) ||
                    name.contains("Atomic", ignoreCase = true) ||
                    name.contains("Fission", ignoreCase = true) -> Wmd.NUCLEAR
                else -> null
            }

        /**
         * Whether an aircraft is a helicopter rather than a fixed wing.
         *
         * Read off the name, because the name is the only place the catalogue records it and adding
         * a column to eleven hundred rows to say what "Attack Helicopter" already says would be a
         * worse kind of duplication. It matters because a helicopter and a jet are drawn
         * differently, and a formation where every aircraft is a jet loses the distinction the
         * catalogue went to the trouble of making.
         */
        val isRotaryWing: Boolean
            get() = weaponClass == WeaponClass.AIRCRAFT &&
                (name.contains("Helicopter", ignoreCase = true) ||
                    name.contains("VTOL", ignoreCase = true) ||
                    name.contains("Rotor", ignoreCase = true))

        /** A crewed vehicle takes more killing than the soldier it replaces. */
        val crewSurvivability: Double
            get() = when {
                !weaponClass.isCrewed -> 1.0
                weaponClass == WeaponClass.ARMOUR -> 3.4
                weaponClass == WeaponClass.AIRCRAFT -> 2.2
                weaponClass == WeaponClass.ARTILLERY || weaponClass == WeaponClass.SIEGE -> 1.8
                else -> 2.0
            }

        /** Battle ticks between shots. The simulation runs at [Battle.TICKS_PER_SECOND]. */
        val cooldownTicks: Int
            get() {
                val shotsPerSecond = weaponClass.rate * (1.0 + 0.45 * tier)
                return (Battle.TICKS_PER_SECOND / shotsPerSecond).toInt().coerceAtLeast(1)
            }

        /** XP to research. Researching costs XP, as the design asks; it is paid back by winning. */
        val researchCost: Long get() = (60 + 5_400 * tier * tier + 380 * tier).toLong()

        /** A soldier carrying this is worth this much in a defence estimate. */
        val threat: Int get() = damage * (1 + splash) * (1 + range / 6)
    }

    // -- Medieval, levels 2..250 ----------------------------------------------

    private val MEDIEVAL = """
        wooden_club|Wooden Club|BLUNT
        sharpened_stick|Sharpened Stick|POLEARM
        sling|Sling|THROWN
        stone_axe|Stone Axe|BLADE
        hunting_bow|Hunting Bow|BOW
        flint_knife|Flint Knife|BLADE
        throwing_rock|Throwing Rock|THROWN
        quarterstaff|Quarterstaff|BLUNT
        bronze_dagger|Bronze Dagger|BLADE
        bronze_spear|Bronze Spear|POLEARM
        shepherds_sling|Shepherd's Sling|THROWN
        hand_axe|Hand Axe|BLADE
        short_bow|Short Bow|BOW
        bronze_sword|Bronze Sword|BLADE
        wooden_shield_bash|Shield Boss|BLUNT
        javelin|Javelin|THROWN
        iron_dagger|Iron Dagger|BLADE
        iron_spear|Iron Spear|POLEARM
        war_club|War Club|BLUNT
        hunting_spear|Hunting Spear|POLEARM
        composite_bow|Composite Bow|BOW
        iron_sword|Iron Sword|BLADE
        bearded_axe|Bearded Axe|BLADE
        throwing_axe|Throwing Axe|THROWN
        sickle_blade|War Sickle|BLADE
        pitchfork|Pitchfork|POLEARM
        scythe_blade|War Scythe|POLEARM
        boar_spear|Boar Spear|POLEARM
        recurve_bow|Recurve Bow|BOW
        stone_maul|Stone Maul|BLUNT
        seax|Seax|BLADE
        framea|Framea|POLEARM
        angon|Angon|THROWN
        francisca|Francisca|THROWN
        spatha|Spatha|BLADE
        gladius|Gladius|BLADE
        pilum|Pilum|THROWN
        scutum_punch|Shield Punch|BLUNT
        hasta|Hasta|POLEARM
        plumbata|Plumbata|THROWN
        steel_dagger|Steel Dagger|BLADE
        arming_sword|Arming Sword|BLADE
        mace|Mace|BLUNT
        flanged_mace|Flanged Mace|BLUNT
        morning_star|Morning Star|BLUNT
        war_hammer|War Hammer|BLUNT
        battle_axe|Battle Axe|BLADE
        dane_axe|Dane Axe|BLADE
        falchion|Falchion|BLADE
        messer|Messer|BLADE
        longsword|Longsword|BLADE
        estoc|Estoc|BLADE
        rondel|Rondel Dagger|BLADE
        misericorde|Misericorde|BLADE
        pike|Pike|POLEARM
        halberd|Halberd|POLEARM
        bardiche|Bardiche|POLEARM
        glaive|Glaive|POLEARM
        voulge|Voulge|POLEARM
        bill_hook|Bill Hook|POLEARM
        partisan|Partisan|POLEARM
        ranseur|Ranseur|POLEARM
        spetum|Spetum|POLEARM
        lucerne_hammer|Lucerne Hammer|POLEARM
        bec_de_corbin|Bec de Corbin|POLEARM
        guisarme|Guisarme|POLEARM
        ahlspiess|Ahlspiess|POLEARM
        lance|Cavalry Lance|POLEARM
        couched_lance|Couched Lance|POLEARM
        flail|Flail|BLUNT
        chain_flail|Chain Flail|BLUNT
        holy_water_sprinkler|Holy Water Sprinkler|BLUNT
        goedendag|Goedendag|BLUNT
        maul|Great Maul|BLUNT
        horsemans_pick|Horseman's Pick|BLUNT
        war_pick|War Pick|BLUNT
        longbow|English Longbow|BOW
        yumi|Yumi|BOW
        horn_bow|Horn Bow|BOW
        turkish_bow|Turkish Bow|BOW
        steppe_bow|Steppe Bow|BOW
        war_bow|War Bow|BOW
        bodkin_bow|Bodkin Longbow|BOW
        light_crossbow|Light Crossbow|CROSSBOW
        crossbow|Crossbow|CROSSBOW
        arbalest|Arbalest|CROSSBOW
        windlass_crossbow|Windlass Crossbow|CROSSBOW
        cranequin|Cranequin Crossbow|CROSSBOW
        repeating_crossbow|Repeating Crossbow|CROSSBOW
        pavise_crossbow|Pavise Crossbow|CROSSBOW
        stonebow|Stonebow|CROSSBOW
        atlatl|Atlatl|THROWN
        darts|Weighted Darts|THROWN
        chakram|Chakram|THROWN
        bolas|Bolas|THROWN
        harpoon|Harpoon|THROWN
        fire_pot|Fire Pot|THROWN
        caltrop_bag|Caltrop Bag|THROWN
        quicklime_jar|Quicklime Jar|THROWN
        beehive_bomb|Beehive Bomb|THROWN
        greek_fire_siphon|Greek Fire Siphon|SIEGE
        battering_ram|Battering Ram|SIEGE
        capped_ram|Capped Ram|SIEGE
        siege_tower|Siege Tower|SIEGE
        siege_ladder|Siege Ladder|SIEGE
        sow|Siege Sow|SIEGE
        mantlet|Mantlet|SUPPORT
        pavise|Pavise|SUPPORT
        ballista|Ballista|SIEGE
        scorpion|Scorpion|SIEGE
        polybolos|Polybolos|SIEGE
        onager|Onager|SIEGE
        mangonel|Mangonel|SIEGE
        catapult|Catapult|SIEGE
        springald|Springald|SIEGE
        couillard|Couillard|SIEGE
        trebuchet|Trebuchet|SIEGE
        counterweight_trebuchet|Counterweight Trebuchet|SIEGE
        warwolf|Warwolf|SIEGE
        petard|Petard|EXPLOSIVE
        sapper_charge|Sapper's Charge|EXPLOSIVE
        mining_gallery|Mining Charge|EXPLOSIVE
        fire_arrow|Fire Arrow|BOW
        incendiary_bolt|Incendiary Bolt|CROSSBOW
        naphtha_pot|Naphtha Pot|THROWN
        hand_cannon|Hand Cannon|FIREARM
        fire_lance|Fire Lance|FIREARM
        arquebus|Arquebus|FIREARM
        matchlock|Matchlock Musket|FIREARM
        caliver|Caliver|FIREARM
        wheellock|Wheellock Pistol|FIREARM
        snaphance|Snaphance Musket|FIREARM
        flintlock|Flintlock Musket|FIREARM
        blunderbuss|Blunderbuss|FIREARM
        dragon_pistol|Dragon Pistol|FIREARM
        musketoon|Musketoon|FIREARM
        jezail|Jezail|FIREARM
        rifled_musket|Rifled Musket|FIREARM
        baker_rifle|Baker Rifle|FIREARM
        organ_gun|Organ Gun|ARTILLERY
        ribauldequin|Ribauldequin|ARTILLERY
        bombard|Bombard|ARTILLERY
        great_bombard|Great Bombard|ARTILLERY
        mortar|Siege Mortar|ARTILLERY
        culverin|Culverin|ARTILLERY
        saker|Saker|ARTILLERY
        falconet|Falconet|ARTILLERY
        demi_cannon|Demi-Cannon|ARTILLERY
        cannon_royal|Cannon Royal|ARTILLERY
        carronade|Carronade|ARTILLERY
        howitzer_early|Early Howitzer|ARTILLERY
        grapeshot|Grapeshot Load|ARTILLERY
        canister_shot|Canister Shot|ARTILLERY
        chain_shot|Chain Shot|ARTILLERY
        heated_shot|Heated Shot|ARTILLERY
        powder_keg|Powder Keg|EXPLOSIVE
        grenado|Grenado|EXPLOSIVE
        hand_mortar|Hand Mortar|EXPLOSIVE
        fire_ship|Fire Ship Charge|EXPLOSIVE
        stink_pot|Stink Pot|EXPLOSIVE
        war_horn|War Horn|SUPPORT
        standard_bearer|Standard|SUPPORT
        drummer|War Drum|SUPPORT
        surgeon_kit|Surgeon's Kit|SUPPORT
        smoke_bundle|Smoke Bundle|SUPPORT
        oil_flask|Oil Flask|THROWN
        heated_sand|Heated Sand|THROWN
        wall_hook|Wall Hook|SUPPORT
        grappling_line|Grappling Line|SUPPORT
        scaling_hook|Scaling Hook|SUPPORT
        zweihander|Zweihänder|BLADE
        claymore|Claymore|BLADE
        flamberge|Flamberge|BLADE
        katana|Katana|BLADE
        nodachi|Nodachi|BLADE
        naginata|Naginata|POLEARM
        yari|Yari|POLEARM
        kanabo|Kanabō|BLUNT
        tetsubo|Tetsubō|BLUNT
        dao|Dao|BLADE
        jian|Jian|BLADE
        guandao|Guandao|POLEARM
        ji_halberd|Ji|POLEARM
        kilij|Kilij|BLADE
        shamshir|Shamshir|BLADE
        talwar|Talwar|BLADE
        khopesh|Khopesh|BLADE
        urumi|Urumi|BLADE
        katar|Katar|BLADE
        kukri|Kukri|BLADE
        assegai|Assegai|POLEARM
        knobkerrie|Knobkerrie|BLUNT
        macuahuitl|Macuahuitl|BLADE
        tepoztopilli|Tepoztopilli|POLEARM
        aztec_atlatl|War Atlatl|THROWN
        war_elephant_howdah|Howdah Bow|BOW
        chariot_bow|Chariot Bow|BOW
        cataphract_lance|Cataphract Lance|POLEARM
        knight_lance|Knight's Lance|POLEARM
        pike_square|Pike Square Drill|SUPPORT
        tercio_drill|Tercio Drill|SUPPORT
        volley_drill|Volley Fire Drill|SUPPORT
        plate_harness|Plate Harness|SUPPORT
        brigandine|Brigandine|SUPPORT
        kite_shield|Kite Shield|SUPPORT
        tower_shield|Tower Shield|SUPPORT
        buckler|Buckler|SUPPORT
        fire_hardened_spear|Fire-Hardened Spear|POLEARM
        antler_pick|Antler Pick|BLUNT
        bone_dagger|Bone Dagger|BLADE
        obsidian_blade|Obsidian Blade|BLADE
        copper_axe|Copper Axe|BLADE
        copper_mace|Copper Mace|BLUNT
        sickle_sword|Sickle Sword|BLADE
        bronze_axe|Bronze Axe|BLADE
        bronze_mace|Bronze Mace|BLUNT
        socketed_spear|Socketed Spear|POLEARM
        leaf_blade|Leaf-Blade Sword|BLADE
        war_flail_early|Threshing Flail|BLUNT
        wooden_maul|Wooden Maul|BLUNT
        hunting_sling|Hunting Sling|THROWN
        staff_sling|Staff Sling|THROWN
        throwing_stick|Throwing Stick|THROWN
        fire_pot_early|Pitch Pot|THROWN
        stone_hammer|Stone War Hammer|BLUNT
        iron_pick|Iron War Pick|BLUNT
        iron_hatchet|Iron Hatchet|BLADE
        boar_tusk_club|Tusk Club|BLUNT
        antler_spear|Antler Spear|POLEARM
        wicker_shield|Wicker Shield|SUPPORT
        hide_shield|Hide Shield|SUPPORT
        round_shield|Round Shield|SUPPORT
        leather_jerkin|Leather Jerkin|SUPPORT
        padded_gambeson|Padded Gambeson|SUPPORT
        scale_shirt|Scale Shirt|SUPPORT
        lamellar_vest|Lamellar Vest|SUPPORT
        mail_hauberk|Mail Hauberk|SUPPORT
        mail_coif|Mail Coif|SUPPORT
        nasal_helm|Nasal Helm|SUPPORT
        spangenhelm|Spangenhelm|SUPPORT
        great_helm|Great Helm|SUPPORT
        bascinet|Bascinet|SUPPORT
        sallet|Sallet|SUPPORT
        armet|Armet|SUPPORT
        coat_of_plates|Coat of Plates|SUPPORT
        splinted_greaves|Splinted Greaves|SUPPORT
        gauntlets|Plate Gauntlets|SUPPORT
        barding|Horse Barding|SUPPORT
        caparison|Caparison|SUPPORT
        war_saddle|War Saddle|SUPPORT
        stirrups|Stirrups|SUPPORT
        spurs|Spurs|SUPPORT
        horn_of_muster|Muster Horn|SUPPORT
        battle_standard|Battle Standard|SUPPORT
        oriflamme|Oriflamme|SUPPORT
        pennon|Pennon|SUPPORT
        marshal_baton|Marshal Baton|SUPPORT
        scout_glass|Scout Glass|SUPPORT
        signal_mirror|Signal Mirror|SUPPORT
        caltrops_scatter|Scattered Caltrops|SUPPORT
        rope_ladder|Rope Ladder|SUPPORT
        siege_hook|Siege Hook|SUPPORT
        crow_of_defence|Defence Crow|SUPPORT
        wolf_pit|Wolf Pit|SUPPORT
        sudis_stake|Sudis Stake|SUPPORT
        portable_pavise|Portable Pavise|SUPPORT
        mantlet_wheeled|Wheeled Mantlet|SUPPORT
        scaling_ladder|Scaling Ladder|SIEGE
        siege_crane|Siege Crane|SIEGE
        bore_ram|Boring Ram|SIEGE
        tortoise_shed|Tortoise Shed|SIEGE
        cat_shed|Cat|SIEGE
        belfry|Belfry|SIEGE
        mining_props|Mining Props|SIEGE
        counter_mine|Counter Mine|SIEGE
        battering_screw|Battering Screw|SIEGE
        bricole|Bricole|SIEGE
        perrier|Perrier|SIEGE
        mangon|Mangon|SIEGE
        espringal|Espringal|SIEGE
        carroballista|Carroballista|SIEGE
        cheiroballistra|Cheiroballistra|SIEGE
        gastraphetes|Gastraphetes|CROSSBOW
        oxybeles|Oxybeles|SIEGE
        lithobolos|Lithobolos|SIEGE
        helepolis|Helepolis|SIEGE
        sambuca|Sambuca|SIEGE
        corvus|Corvus|SIEGE
        harpax|Harpax|SIEGE
        fire_ship_medieval|Fire Ship|SIEGE
        greek_fire_grenade|Greek Fire Grenade|THROWN
        naphtha_lamp|Naphtha Lamp|THROWN
        quicklime_bomb|Quicklime Bomb|THROWN
        scorpion_jar|Scorpion Jar|THROWN
        serpent_jar|Serpent Jar|THROWN
        dung_pot|Dung Pot|THROWN
        smoke_pot|Smoke Pot|THROWN
        stink_bomb_medieval|Stink Bomb|THROWN
        flaming_pig|Flaming Pig|SIEGE
        trebuchet_carcass|Carcass Shot|SIEGE
        plague_carcass|Plague Carcass|SIEGE
        hot_sand_pot|Hot Sand Pot|THROWN
        boiling_pitch|Boiling Pitch|SIEGE
        murder_beam|Murder Beam|SIEGE
        drop_stone|Drop Stone|SIEGE
        wall_scythe|Wall Scythe|POLEARM
        gate_axe|Gate Axe|BLADE
        door_maul|Door Maul|BLUNT
        wall_pick|Wall Pick|BLUNT
        crow_bar|Crow Bar|BLUNT
        grappling_hook_war|War Grapnel|SUPPORT
        boarding_axe|Boarding Axe|BLADE
        boarding_pike|Boarding Pike|POLEARM
        naval_ram|Naval Ram|SIEGE
        ballista_bolt_heavy|Heavy Bolt|SIEGE
        chain_bolt|Chain Bolt|SIEGE
        fire_arrow_heavy|Heavy Fire Arrow|BOW
        rope_cutter|Rope Cutter|BLADE
        sail_hook|Sail Hook|SUPPORT
        hand_culverin|Hand Culverin|FIREARM
        petronel|Petronel|FIREARM
        serpentine_lock|Serpentine Lock Gun|FIREARM
        doglock|Doglock Musket|FIREARM
        miquelet|Miquelet Musket|FIREARM
        espingole|Espingole|FIREARM
        amusette|Amusette|FIREARM
        wall_gun|Wall Gun|FIREARM
        rampart_gun|Rampart Gun|FIREARM
        swivel_gun|Swivel Gun|ARTILLERY
        murderer_gun|Murderer|ARTILLERY
        perier_gun|Perier|ARTILLERY
        veuglaire|Veuglaire|ARTILLERY
        crapaudeau|Crapaudeau|ARTILLERY
        serpentine_gun|Serpentine|ARTILLERY
        basilisk|Basilisk|ARTILLERY
        cannon_serpentine|Cannon Serpentine|ARTILLERY
        double_cannon|Double Cannon|ARTILLERY
        mortar_petard|Petard Mortar|ARTILLERY
        stone_mortar|Stone Mortar|ARTILLERY
        coehorn|Coehorn Mortar|ARTILLERY
        leather_gun|Leather Gun|ARTILLERY
        galloper_gun|Galloper Gun|ARTILLERY
        regimental_gun|Regimental Gun|ARTILLERY
        battering_piece|Battering Piece|ARTILLERY
        siege_train_gun|Siege Train Gun|ARTILLERY
        bar_shot|Bar Shot|ARTILLERY
        langrage|Langrage|ARTILLERY
        spike_shot|Spike Shot|ARTILLERY
        double_headed_shot|Double-Headed Shot|ARTILLERY
        burning_shot|Burning Shot|ARTILLERY
        powder_barrel|Powder Barrel|EXPLOSIVE
        fougasse|Fougasse|EXPLOSIVE
        mine_gallery|Mine Gallery Charge|EXPLOSIVE
        camouflet|Camouflet|EXPLOSIVE
        springing_mine|Springing Mine|EXPLOSIVE
        hand_grenado|Hand Grenado|EXPLOSIVE
        grenade_ball|Grenade Ball|EXPLOSIVE
        fire_lance_heavy|Heavy Fire Lance|FIREARM
        rocket_arrow|Rocket Arrow|EXPLOSIVE
        fire_cart|Fire Cart|EXPLOSIVE
        nest_of_bees|Nest of Bees|EXPLOSIVE
        flying_crow|Flying Crow|EXPLOSIVE
        thunder_crash_bomb|Thunder Crash Bomb|EXPLOSIVE
        eruptor|Eruptor|FIREARM
        bamboo_gun|Bamboo Gun|FIREARM
        three_barrel_gun|Three-Barrel Gun|FIREARM
        ribauld|Ribauld|ARTILLERY
        orgue_de_bombardes|Orgue de Bombardes|ARTILLERY
        crossbow_arbalest_heavy|Siege Arbalest|CROSSBOW
        chu_ko_nu|Chu Ko Nu|CROSSBOW
        latchet_crossbow|Latchet Crossbow|CROSSBOW
        goat_foot_crossbow|Goat's Foot Crossbow|CROSSBOW
        belt_hook_crossbow|Belt Hook Crossbow|CROSSBOW
        prod_crossbow|Steel Prod Crossbow|CROSSBOW
        slurbow|Slurbow|CROSSBOW
        bullet_crossbow|Bullet Crossbow|CROSSBOW
        hunting_crossbow|Hunting Crossbow|CROSSBOW
        siege_crossbow_mount|Mounted Crossbow|CROSSBOW
        flat_bow|Flat Bow|BOW
        self_bow|Self Bow|BOW
        cable_backed_bow|Cable-Backed Bow|BOW
        sinew_bow|Sinew-Backed Bow|BOW
        magyar_bow|Magyar Bow|BOW
        scythian_bow|Scythian Bow|BOW
        manchu_bow|Manchu Bow|BOW
        korean_gakgung|Gakgung|BOW
        daikyu|Daikyu|BOW
        hankyu|Hankyu|BOW
        penobscot_bow|Penobscot Bow|BOW
        war_bow_heavy|Heavy War Bow|BOW
        bodkin_arrow|Bodkin Arrow|BOW
        broadhead_arrow|Broadhead Arrow|BOW
        swallowtail_arrow|Swallowtail Arrow|BOW
        whistling_arrow|Whistling Arrow|BOW
        poison_arrow|Poisoned Arrow|BOW
        rope_arrow|Rope Arrow|BOW
        falarica|Falarica|THROWN
        soliferrum|Soliferrum|THROWN
        verutum|Verutum|THROWN
        spiculum|Spiculum|THROWN
        martiobarbulus|Martiobarbulus|THROWN
        cateia|Cateia|THROWN
        aclys|Aclys|THROWN
        amentum_javelin|Amentum Javelin|THROWN
        harpoon_war|War Harpoon|THROWN
        net_and_trident|Net and Trident|POLEARM
        trident|Trident|POLEARM
        military_fork|Military Fork|POLEARM
        corseque|Corseque|POLEARM
        brandistock|Brandistock|POLEARM
        bohemian_earspoon|Bohemian Earspoon|POLEARM
        awl_pike|Awl Pike|POLEARM
        boar_spear_winged|Winged Boar Spear|POLEARM
        lochaber_axe|Lochaber Axe|POLEARM
        jeddart_staff|Jeddart Staff|POLEARM
        sovnya|Sovnya|POLEARM
        berdiche|Berdiche|POLEARM
        dagger_axe_ge|Ge Dagger-Axe|POLEARM
        pudao|Pudao|POLEARM
        nagamaki|Nagamaki|POLEARM
        bisento|Bisento|POLEARM
        sasumata|Sasumata|POLEARM
        tsukubo|Tsukubo|POLEARM
        sodegarami|Sodegarami|POLEARM
        kama_yari|Kama Yari|POLEARM
        wakizashi|Wakizashi|BLADE
        tanto|Tanto|BLADE
        tachi|Tachi|BLADE
        uchigatana|Uchigatana|BLADE
        chokuto|Chokuto|BLADE
        changdao|Changdao|BLADE
        miao_dao|Miao Dao|BLADE
        butterfly_swords|Butterfly Swords|BLADE
        hook_swords|Hook Swords|BLADE
        nine_ring_dao|Nine-Ring Dao|BLADE
        scimitar|Scimitar|BLADE
        saif|Saif|BLADE
        nimcha|Nimcha|BLADE
        yatagan|Yatagan|BLADE
        pata|Pata|BLADE
        khanda|Khanda|BLADE
        firangi|Firangi|BLADE
        tulwar_heavy|Heavy Tulwar|BLADE
        kris|Kris|BLADE
        klewang|Klewang|BLADE
        parang|Parang|BLADE
        dha|Dha|BLADE
        takouba|Takouba|BLADE
        kaskara|Kaskara|BLADE
        shotel|Shotel|BLADE
        ida_sword|Ida|BLADE
        nimcha_kabyle|Kabyle Nimcha|BLADE
        falx|Falx|BLADE
        rhomphaia|Rhomphaia|BLADE
        sica|Sica|BLADE
        kopis|Kopis|BLADE
        xiphos|Xiphos|BLADE
        makhaira|Makhaira|BLADE
        celtic_longsword|Celtic Longsword|BLADE
        gladius_hispaniensis|Gladius Hispaniensis|BLADE
        semispatha|Semispatha|BLADE
        langseax|Langseax|BLADE
        ulfberht|Ulfberht Sword|BLADE
        viking_axe|Viking Axe|BLADE
        skeggox|Skeggox|BLADE
        breidox|Breidox|BLADE
        war_scythe_heavy|Heavy War Scythe|POLEARM
        peasant_flail|Peasant Flail|BLUNT
        threshing_stick|Threshing Stick|BLUNT
        shepherds_crook|Shepherd's Crook|BLUNT
        quarter_staff_iron|Iron-Shod Staff|BLUNT
        tetsubo_heavy|Heavy Tetsubo|BLUNT
        gada|Gada|BLUNT
        shishpar|Shishpar|BLUNT
        bulawa|Bulawa|BLUNT
        pernach|Pernach|BLUNT
        buzdygan|Buzdygan|BLUNT
        nadziak|Nadziak|BLUNT
        czekan|Czekan|BLUNT
        fokos|Fokos|BLUNT
        valaska|Valaska|BLUNT
        bardiche_heavy|Heavy Bardiche|POLEARM
    """.trimIndent()

    // -- Modern, levels 251..312 ----------------------------------------------

    private val MODERN = """
        bolt_rifle|Bolt-Action Rifle|FIREARM
        lever_rifle|Lever-Action Rifle|FIREARM
        service_revolver|Service Revolver|FIREARM
        trench_shotgun|Trench Shotgun|FIREARM
        semi_rifle|Semi-Automatic Rifle|FIREARM
        carbine|Carbine|FIREARM
        marksman_rifle|Marksman Rifle|FIREARM
        sniper_rifle|Sniper Rifle|FIREARM
        anti_materiel|Anti-Materiel Rifle|FIREARM
        service_pistol|Service Pistol|FIREARM
        machine_pistol|Machine Pistol|AUTOMATIC
        submachine_gun|Submachine Gun|AUTOMATIC
        assault_rifle|Assault Rifle|AUTOMATIC
        bullpup_rifle|Bullpup Rifle|AUTOMATIC
        light_machine_gun|Light Machine Gun|AUTOMATIC
        medium_machine_gun|Medium Machine Gun|AUTOMATIC
        heavy_machine_gun|Heavy Machine Gun|AUTOMATIC
        water_cooled_mg|Water-Cooled MG|AUTOMATIC
        minigun|Rotary Minigun|AUTOMATIC
        squad_automatic|Squad Automatic Weapon|AUTOMATIC
        chain_gun|Chain Gun|AUTOMATIC
        autocannon|Autocannon|AUTOMATIC
        flak_cannon|Flak Cannon|ARTILLERY
        field_gun|Field Gun|ARTILLERY
        pack_howitzer|Pack Howitzer|ARTILLERY
        field_howitzer|Field Howitzer|ARTILLERY
        heavy_howitzer|Heavy Howitzer|ARTILLERY
        siege_howitzer|Siege Howitzer|ARTILLERY
        railway_gun|Railway Gun|ARTILLERY
        coastal_gun|Coastal Gun|ARTILLERY
        mortar_60|60mm Mortar|ARTILLERY
        mortar_81|81mm Mortar|ARTILLERY
        mortar_120|120mm Mortar|ARTILLERY
        rocket_artillery|Rocket Artillery|ARTILLERY
        multiple_launcher|Multiple Rocket Launcher|ARTILLERY
        self_propelled_gun|Self-Propelled Gun|ARTILLERY
        counter_battery|Counter-Battery Radar|SUPPORT
        hand_grenade|Hand Grenade|EXPLOSIVE
        stick_grenade|Stick Grenade|EXPLOSIVE
        rifle_grenade|Rifle Grenade|EXPLOSIVE
        grenade_launcher|Grenade Launcher|EXPLOSIVE
        automatic_grenade|Automatic Grenade Launcher|EXPLOSIVE
        satchel_charge|Satchel Charge|EXPLOSIVE
        shaped_charge|Shaped Charge|EXPLOSIVE
        demolition_charge|Demolition Charge|EXPLOSIVE
        anti_tank_mine|Anti-Tank Mine|EXPLOSIVE
        anti_personnel_mine|Anti-Personnel Mine|EXPLOSIVE
        claymore_mine|Directional Mine|EXPLOSIVE
        bangalore|Bangalore Torpedo|EXPLOSIVE
        flamethrower|Flamethrower|EXPLOSIVE
        thermobaric_charge|Thermobaric Charge|EXPLOSIVE
        bazooka|Bazooka|MISSILE
        recoilless_rifle|Recoilless Rifle|MISSILE
        rpg|Rocket-Propelled Grenade|MISSILE
        atgm|Anti-Tank Guided Missile|MISSILE
        manpads|Shoulder-Launched SAM|MISSILE
        cruise_missile|Cruise Missile|MISSILE
        ballistic_missile|Ballistic Missile|MISSILE
        anti_ship_missile|Anti-Ship Missile|MISSILE
        loitering_munition|Loitering Munition|MISSILE
        armoured_car|Armoured Car|ARMOUR
        half_track|Half-Track|ARMOUR
        light_tank|Light Tank|ARMOUR
        medium_tank|Medium Tank|ARMOUR
        heavy_tank|Heavy Tank|ARMOUR
        main_battle_tank|Main Battle Tank|ARMOUR
        tank_destroyer|Tank Destroyer|ARMOUR
        assault_gun|Assault Gun|ARMOUR
        infantry_carrier|Infantry Fighting Vehicle|ARMOUR
        mine_flail_tank|Mine Flail Tank|ARMOUR
        bridge_layer|Armoured Bridge Layer|SUPPORT
        recovery_vehicle|Armoured Recovery Vehicle|SUPPORT
        spaa|Self-Propelled AA|ARMOUR
        biplane|Biplane Scout|AIRCRAFT
        fighter_plane|Fighter|AIRCRAFT
        interceptor|Interceptor|AIRCRAFT
        jet_fighter|Jet Fighter|AIRCRAFT
        strike_fighter|Strike Fighter|AIRCRAFT
        ground_attack|Ground Attack Aircraft|AIRCRAFT
        dive_bomber|Dive Bomber|AIRCRAFT
        level_bomber|Level Bomber|AIRCRAFT
        heavy_bomber|Heavy Bomber|AIRCRAFT
        strategic_bomber|Strategic Bomber|AIRCRAFT
        attack_helicopter|Attack Helicopter|AIRCRAFT
        transport_helicopter|Transport Helicopter|SUPPORT
        gunship|Fixed-Wing Gunship|AIRCRAFT
        recon_drone|Reconnaissance Drone|DRONE
        strike_drone|Strike Drone|DRONE
        napalm_run|Napalm Run|AIRCRAFT
        cluster_bomb|Cluster Bomb|AIRCRAFT
        bunker_buster|Bunker Buster|AIRCRAFT
        guided_bomb|Guided Bomb|AIRCRAFT
        smoke_screen|Smoke Screen|SUPPORT
        field_radio|Field Radio|SUPPORT
        forward_observer|Forward Observer|SUPPORT
        medic_pack|Combat Medic Pack|SUPPORT
        sandbag_line|Sandbag Line|SUPPORT
        razor_wire|Razor Wire|SUPPORT
        searchlight|Searchlight|SUPPORT
        radar_set|Radar Set|SUPPORT
        jammer|Radio Jammer|SUPPORT
        night_optics|Night Optics|SUPPORT
        body_armour|Body Armour|SUPPORT
        composite_armour|Composite Armour|SUPPORT
        reactive_armour|Reactive Armour|SUPPORT
        engineer_kit|Combat Engineer Kit|SUPPORT
        breaching_charge|Breaching Charge|EXPLOSIVE
        sniper_optics|Sniper Optics|SUPPORT
        designator|Laser Designator|SUPPORT
        gps_guidance|Satellite Guidance|SUPPORT
        supply_drop|Supply Drop|SUPPORT
        artillery_computer|Artillery Computer|SUPPORT
        ciws|Close-In Weapon System|AUTOMATIC
        sam_battery|Surface-to-Air Battery|MISSILE
        mobile_sam|Mobile SAM|MISSILE
        coastal_missile|Coastal Missile Battery|MISSILE
        torpedo|Torpedo|EXPLOSIVE
        depth_charge|Depth Charge|EXPLOSIVE
        naval_gun|Naval Gun|ARTILLERY
        needle_gun|Needle Gun|FIREARM
        chassepot|Chassepot|FIREARM
        martini_henry|Martini-Henry|FIREARM
        trapdoor_rifle|Trapdoor Rifle|FIREARM
        krag_rifle|Krag Rifle|FIREARM
        mannlicher|Mannlicher Rifle|FIREARM
        mosin_pattern|Mosin Pattern Rifle|FIREARM
        lee_enfield_pattern|Lee-Enfield Pattern|FIREARM
        springfield_pattern|Springfield Pattern|FIREARM
        carcano_pattern|Carcano Pattern|FIREARM
        arisaka_pattern|Arisaka Pattern|FIREARM
        garand_pattern|Self-Loading Battle Rifle|FIREARM
        battle_rifle_762|7.62 Battle Rifle|AUTOMATIC
        designated_marksman|Designated Marksman Rifle|FIREARM
        scout_rifle|Scout Rifle|FIREARM
        hunting_carbine|Hunting Carbine|FIREARM
        lever_carbine|Lever Carbine|FIREARM
        pump_shotgun|Pump Shotgun|FIREARM
        semi_shotgun|Semi-Auto Shotgun|FIREARM
        combat_shotgun|Combat Shotgun|AUTOMATIC
        breaching_shotgun|Breaching Shotgun|FIREARM
        flare_pistol|Flare Pistol|SUPPORT
        signal_pistol|Signal Pistol|SUPPORT
        derringer|Derringer|FIREARM
        service_automatic|Service Automatic|FIREARM
        heavy_revolver|Heavy Revolver|FIREARM
        broomhandle|Broomhandle Pistol|FIREARM
        luger_pattern|Toggle-Lock Pistol|FIREARM
        silenced_pistol|Silenced Pistol|FIREARM
        holdout_pistol|Hold-Out Pistol|FIREARM
        smg_blowback|Blowback SMG|AUTOMATIC
        smg_folding|Folding-Stock SMG|AUTOMATIC
        smg_suppressed|Suppressed SMG|AUTOMATIC
        pdw|Personal Defence Weapon|AUTOMATIC
        carbine_auto|Automatic Carbine|AUTOMATIC
        assault_rifle_early|Sturmgewehr Pattern|AUTOMATIC
        assault_rifle_556|5.56 Assault Rifle|AUTOMATIC
        assault_rifle_545|5.45 Assault Rifle|AUTOMATIC
        battle_carbine|Battle Carbine|AUTOMATIC
        lmg_belt|Belt-Fed LMG|AUTOMATIC
        lmg_drum|Drum-Fed LMG|AUTOMATIC
        gpmg|General Purpose MG|AUTOMATIC
        hmg_50|Heavy MG|AUTOMATIC
        coax_mg|Coaxial MG|AUTOMATIC
        aircraft_mg|Aircraft MG|AUTOMATIC
        gatling_early|Gatling Gun|AUTOMATIC
        nordenfelt|Nordenfelt Gun|AUTOMATIC
        mitrailleuse|Mitrailleuse|AUTOMATIC
        pom_pom|Pom-Pom Gun|AUTOMATIC
        autocannon_20|20mm Autocannon|AUTOMATIC
        autocannon_30|30mm Autocannon|AUTOMATIC
        autocannon_40|40mm Autocannon|AUTOMATIC
        revolver_cannon|Revolver Cannon|AUTOMATIC
        rotary_cannon|Rotary Cannon|AUTOMATIC
        anti_materiel_50|.50 Anti-Materiel Rifle|FIREARM
        anti_tank_rifle|Anti-Tank Rifle|FIREARM
        rifle_grenade_launcher|Rifle Grenade Launcher|EXPLOSIVE
        underbarrel_launcher|Underbarrel Launcher|EXPLOSIVE
        revolver_launcher|Revolver Grenade Launcher|EXPLOSIVE
        agl|Automatic Grenade Launcher|EXPLOSIVE
        light_mortar|Light Mortar|ARTILLERY
        medium_mortar|Medium Mortar|ARTILLERY
        heavy_mortar|Heavy Mortar|ARTILLERY
        breech_mortar|Breech-Loading Mortar|ARTILLERY
        spigot_mortar|Spigot Mortar|ARTILLERY
        infantry_gun|Infantry Gun|ARTILLERY
        mountain_gun|Mountain Gun|ARTILLERY
        horse_artillery|Horse Artillery|ARTILLERY
        field_howitzer_105|105mm Howitzer|ARTILLERY
        field_howitzer_155|155mm Howitzer|ARTILLERY
        gun_howitzer_203|203mm Gun-Howitzer|ARTILLERY
        siege_gun_280|280mm Siege Gun|ARTILLERY
        super_heavy_gun|Super-Heavy Gun|ARTILLERY
        rail_gun_800|800mm Railway Gun|ARTILLERY
        recoilless_57|57mm Recoilless|MISSILE
        recoilless_106|106mm Recoilless|MISSILE
        anti_tank_gun_37|37mm Anti-Tank Gun|ARTILLERY
        anti_tank_gun_57|57mm Anti-Tank Gun|ARTILLERY
        anti_tank_gun_88|88mm Dual-Purpose Gun|ARTILLERY
        tank_gun_75|75mm Tank Gun|ARMOUR
        tank_gun_90|90mm Tank Gun|ARMOUR
        tank_gun_105|105mm Tank Gun|ARMOUR
        tank_gun_120|120mm Smoothbore|ARMOUR
        tank_gun_125|125mm Smoothbore|ARMOUR
        apfsds_round|APFSDS Round|ARMOUR
        heat_round|HEAT Round|ARMOUR
        hesh_round|HESH Round|ARMOUR
        canister_round|Canister Round|ARMOUR
        smoke_round|Smoke Round|SUPPORT
        illumination_round|Illumination Round|SUPPORT
        white_phosphorus|White Phosphorus Round|ARTILLERY
        base_bleed_shell|Base Bleed Shell|ARTILLERY
        rocket_assisted_shell|Rocket-Assisted Shell|ARTILLERY
        cargo_shell|Cargo Shell|ARTILLERY
        guided_shell|Guided Shell|ARTILLERY
        katyusha_pattern|Truck Rocket Launcher|ARTILLERY
        nebelwerfer_pattern|Towed Rocket Launcher|ARTILLERY
        mlrs_light|Light MLRS|ARTILLERY
        mlrs_heavy|Heavy MLRS|ARTILLERY
        tactical_rocket|Tactical Rocket|MISSILE
        battlefield_missile|Battlefield Missile|MISSILE
        theatre_missile|Theatre Ballistic Missile|MISSILE
        icbm|Intercontinental Missile|MISSILE
        slbm|Submarine-Launched Missile|MISSILE
        anti_radiation_missile|Anti-Radiation Missile|MISSILE
        stand_off_missile|Stand-Off Missile|MISSILE
        glide_bomb|Glide Bomb|AIRCRAFT
        laser_guided_bomb|Laser-Guided Bomb|AIRCRAFT
        gps_guided_bomb|Satellite-Guided Bomb|AIRCRAFT
        penetrator_bomb|Penetrator Bomb|AIRCRAFT
        fuel_air_bomb|Fuel-Air Bomb|AIRCRAFT
        incendiary_bomb|Incendiary Bomb|AIRCRAFT
        leaflet_bomb|Leaflet Bomb|SUPPORT
        depth_bomb|Depth Bomb|EXPLOSIVE
        naval_mine|Naval Mine|EXPLOSIVE
        limpet_mine|Limpet Mine|EXPLOSIVE
        magnetic_mine|Magnetic Mine|EXPLOSIVE
        bounding_mine|Bounding Mine|EXPLOSIVE
        off_route_mine|Off-Route Mine|EXPLOSIVE
        scatterable_mine|Scatterable Mine|EXPLOSIVE
        booby_trap|Booby Trap|EXPLOSIVE
        pipe_charge|Pipe Charge|EXPLOSIVE
        cutting_charge|Cutting Charge|EXPLOSIVE
        wall_breaching_charge|Wall Breaching Charge|EXPLOSIVE
        det_cord|Detonation Cord|EXPLOSIVE
        plastic_explosive|Plastic Explosive|EXPLOSIVE
        thermite_charge|Thermite Charge|EXPLOSIVE
        shaped_demolition|Shaped Demolition Charge|EXPLOSIVE
        flamethrower_backpack|Backpack Flamethrower|EXPLOSIVE
        flame_tank|Flame Tank|ARMOUR
        smoke_generator|Smoke Generator|SUPPORT
        armoured_train|Armoured Train|ARMOUR
        armoured_car_early|Early Armoured Car|ARMOUR
        scout_car|Scout Car|ARMOUR
        wheeled_apc|Wheeled APC|ARMOUR
        tracked_apc|Tracked APC|ARMOUR
        ifv_autocannon|IFV with Autocannon|ARMOUR
        light_tank_early|Interwar Light Tank|ARMOUR
        cruiser_tank|Cruiser Tank|ARMOUR
        infantry_tank|Infantry Tank|ARMOUR
        medium_tank_early|Early Medium Tank|ARMOUR
        heavy_tank_breakthrough|Breakthrough Tank|ARMOUR
        super_heavy_tank|Super-Heavy Tank|ARMOUR
        tank_destroyer_casemate|Casemate Tank Destroyer|ARMOUR
        assault_howitzer|Assault Howitzer|ARMOUR
        self_propelled_howitzer|Self-Propelled Howitzer|ARMOUR
        rocket_tank|Rocket Tank|ARMOUR
        engineering_tank|Engineering Tank|SUPPORT
        mine_roller|Mine Roller|SUPPORT
        dozer_blade|Dozer Blade|SUPPORT
        amphibious_carrier|Amphibious Carrier|ARMOUR
        air_defence_tank|Air Defence Tank|ARMOUR
        command_tank|Command Tank|SUPPORT
        recovery_tank|Recovery Tank|SUPPORT
        observation_balloon|Observation Balloon|SUPPORT
        airship_scout|Scout Airship|AIRCRAFT
        airship_bomber|Bomber Airship|AIRCRAFT
        reconnaissance_plane|Reconnaissance Plane|SUPPORT
        artillery_spotter|Artillery Spotter|SUPPORT
        fighter_biplane|Biplane Fighter|AIRCRAFT
        fighter_monoplane|Monoplane Fighter|AIRCRAFT
        heavy_fighter|Heavy Fighter|AIRCRAFT
        night_fighter|Night Fighter|AIRCRAFT
        escort_fighter|Escort Fighter|AIRCRAFT
        jet_interceptor|Jet Interceptor|AIRCRAFT
        multirole_fighter|Multirole Fighter|AIRCRAFT
        stealth_fighter|Stealth Fighter|AIRCRAFT
        light_bomber|Light Bomber|AIRCRAFT
        medium_bomber|Medium Bomber|AIRCRAFT
        torpedo_bomber|Torpedo Bomber|AIRCRAFT
        dive_bomber_naval|Naval Dive Bomber|AIRCRAFT
        stealth_bomber|Stealth Bomber|AIRCRAFT
        gunship_helicopter|Helicopter Gunship|AIRCRAFT
        scout_helicopter|Scout Helicopter|SUPPORT
        transport_plane|Transport Plane|SUPPORT
        glider_assault|Assault Glider|SUPPORT
        paratrooper_drop|Paratrooper Drop|SUPPORT
        air_refueller|Air Refueller|SUPPORT
        awacs|Airborne Early Warning|SUPPORT
        electronic_warfare_plane|Electronic Warfare Plane|SUPPORT
        recon_satellite|Reconnaissance Satellite|SUPPORT
        comms_satellite|Communications Satellite|SUPPORT
        navigation_satellite|Navigation Satellite|SUPPORT
        torpedo_early|Early Torpedo|EXPLOSIVE
        homing_torpedo|Homing Torpedo|EXPLOSIVE
        wire_guided_torpedo|Wire-Guided Torpedo|EXPLOSIVE
        naval_gun_light|Light Naval Gun|ARTILLERY
        naval_gun_heavy|Heavy Naval Gun|ARTILLERY
        battleship_battery|Battleship Battery|ARTILLERY
        ciws_naval|Naval CIWS|AUTOMATIC
        vls_cell|Vertical Launch Cell|MISSILE
        harpoon_missile|Anti-Ship Missile Battery|MISSILE
        sonar_array|Sonar Array|SUPPORT
        radar_fire_control|Fire Control Radar|SUPPORT
        radar_surveillance|Surveillance Radar|SUPPORT
        radar_counter_battery|Counter-Battery Radar|SUPPORT
        laser_rangefinder|Laser Rangefinder|SUPPORT
        thermal_sight|Thermal Sight|SUPPORT
        night_vision_goggles|Night Vision Goggles|SUPPORT
        ballistic_computer|Ballistic Computer|SUPPORT
        battlefield_radio|Battlefield Radio|SUPPORT
        encrypted_radio|Encrypted Radio|SUPPORT
        field_telephone|Field Telephone|SUPPORT
        radio_direction_finder|Direction Finder|SUPPORT
        jamming_set|Jamming Set|SUPPORT
        decoy_flares|Decoy Flares|SUPPORT
        chaff_dispenser|Chaff Dispenser|SUPPORT
        smoke_dischargers|Smoke Dischargers|SUPPORT
        camouflage_netting|Camouflage Netting|SUPPORT
        ghillie_suit|Ghillie Suit|SUPPORT
        flak_jacket|Flak Jacket|SUPPORT
        ballistic_plate|Ballistic Plate Carrier|SUPPORT
        composite_helmet|Composite Helmet|SUPPORT
        gas_mask|Gas Mask|SUPPORT
        nbc_suit|NBC Suit|SUPPORT
        exoskeleton_load|Load-Bearing Exoskeleton|SUPPORT
        combat_medic_kit|Combat Medic Kit|SUPPORT
        blood_plasma_kit|Blood Plasma Kit|SUPPORT
        entrenching_tool|Entrenching Tool|SUPPORT
        bangalore_set|Bangalore Set|EXPLOSIVE
        mine_detector|Mine Detector|SUPPORT
        bridging_kit|Bridging Kit|SUPPORT
        assault_boat|Assault Boat|SUPPORT
        parachute_rig|Parachute Rig|SUPPORT
        grappling_launcher|Grappling Launcher|SUPPORT
        atomic_bomb|Atomic Bomb|EXOTIC
        fission_warhead|Fission Warhead|MISSILE
        thermonuclear_warhead|Thermonuclear Warhead|MISSILE
        nuclear_torpedo|Nuclear Torpedo|MISSILE
        tactical_nuclear_shell|Tactical Nuclear Shell|ARTILLERY
        nuclear_cruise_missile|Nuclear Cruise Missile|MISSILE
        strategic_nuclear_missile|Strategic Nuclear Missile|MISSILE
    """.trimIndent()

    // -- Futuristic, levels 313..500 ------------------------------------------

    private val FUTURISTIC = """
        laser_carbine|Laser Carbine|BEAM
        laser_rifle|Laser Rifle|BEAM
        pulse_rifle|Pulse Rifle|BEAM
        beam_lance|Beam Lance|BEAM
        heavy_laser|Heavy Laser|BEAM
        scatter_laser|Scatter Laser|BEAM
        continuous_beam|Continuous Beam Projector|BEAM
        prism_beam|Prism Beam|BEAM
        maser|Maser Emitter|BEAM
        particle_beam|Particle Beam|BEAM
        graviton_beam|Graviton Beam|BEAM
        beam_scythe|Beam Scythe|BEAM
        plasma_pistol|Plasma Pistol|PLASMA
        plasma_rifle|Plasma Rifle|PLASMA
        plasma_caster|Plasma Caster|PLASMA
        plasma_lance|Plasma Lance|PLASMA
        plasma_mortar|Plasma Mortar|PLASMA
        plasma_cannon|Plasma Cannon|PLASMA
        fusion_torch|Fusion Torch|PLASMA
        arc_projector|Arc Projector|PLASMA
        ion_caster|Ion Caster|PLASMA
        plasma_grenade|Plasma Grenade|PLASMA
        thermal_lance|Thermal Lance|PLASMA
        coilgun|Coilgun|RAIL
        railgun|Railgun|RAIL
        heavy_railgun|Heavy Railgun|RAIL
        rail_lance|Rail Lance|RAIL
        gauss_rifle|Gauss Rifle|RAIL
        gauss_cannon|Gauss Cannon|RAIL
        mass_driver|Mass Driver|RAIL
        orbital_kinetic|Orbital Kinetic Rod|RAIL
        slug_battery|Slug Battery|RAIL
        magnetic_accelerator|Magnetic Accelerator Cannon|RAIL
        rail_mortar|Rail Mortar|RAIL
        scout_drone|Scout Drone|DRONE
        combat_drone|Combat Drone|DRONE
        swarm_drone|Swarm Drone|DRONE
        heavy_drone|Heavy Drone|DRONE
        sapper_drone|Sapper Drone|DRONE
        interceptor_drone|Interceptor Drone|DRONE
        shield_drone|Shield Drone|SUPPORT
        repair_drone|Repair Drone|SUPPORT
        spotter_drone|Spotter Drone|SUPPORT
        kamikaze_drone|Kamikaze Drone|DRONE
        drone_carrier|Drone Carrier|DRONE
        nanite_swarm|Nanite Swarm|DRONE
        exosuit_fist|Exosuit Fist|BLUNT
        power_blade|Powered Blade|BLADE
        monomolecular_blade|Monomolecular Blade|BLADE
        vibro_axe|Vibro Axe|BLADE
        shock_maul|Shock Maul|BLUNT
        force_hammer|Force Hammer|BLUNT
        grav_gauntlet|Gravitic Gauntlet|BLUNT
        phase_spear|Phase Spear|POLEARM
        arc_glaive|Arc Glaive|POLEARM
        breaching_claw|Breaching Claw|BLADE
        light_mech|Light Mech|ARMOUR
        assault_mech|Assault Mech|ARMOUR
        siege_walker|Siege Walker|ARMOUR
        hover_tank|Hover Tank|ARMOUR
        grav_tank|Gravitic Tank|ARMOUR
        crawler|Armoured Crawler|ARMOUR
        spider_walker|Spider Walker|ARMOUR
        titan_frame|Titan Frame|ARMOUR
        breaching_rig|Breaching Rig|ARMOUR
        shield_walker|Shield Walker|ARMOUR
        interceptor_craft|Interceptor Craft|AIRCRAFT
        strike_craft|Strike Craft|AIRCRAFT
        gunship_vtol|VTOL Gunship|AIRCRAFT
        dropship|Dropship|SUPPORT
        bombard_craft|Bombard Craft|AIRCRAFT
        stealth_craft|Stealth Craft|AIRCRAFT
        aerospace_fighter|Aerospace Fighter|AIRCRAFT
        orbital_bomber|Orbital Bomber|AIRCRAFT
        carrier_wing|Carrier Wing|AIRCRAFT
        smart_missile|Smart Missile|MISSILE
        swarm_missile|Swarm Missile|MISSILE
        hypersonic_missile|Hypersonic Missile|MISSILE
        antimatter_missile|Antimatter Missile|MISSILE
        singularity_torpedo|Singularity Torpedo|EXOTIC
        void_lance|Void Lance|EXOTIC
        phase_torpedo|Phase Torpedo|EXOTIC
        gravity_bomb|Gravity Bomb|EXOTIC
        stasis_charge|Stasis Charge|EXOTIC
        entropy_field|Entropy Field|EXOTIC
        black_hole_charge|Micro-Singularity Charge|EXOTIC
        dimensional_ripper|Dimensional Ripper|EXOTIC
        temporal_snare|Temporal Snare|EXOTIC
        quantum_shredder|Quantum Shredder|EXOTIC
        antimatter_lance|Antimatter Lance|EXOTIC
        neutronium_slug|Neutronium Slug|RAIL
        emp_projector|EMP Projector|SUPPORT
        disruptor_field|Disruptor Field|SUPPORT
        stasis_projector|Stasis Projector|SUPPORT
        cloak_field|Cloaking Field|SUPPORT
        hardlight_shield|Hardlight Shield|SUPPORT
        deflector_pack|Deflector Pack|SUPPORT
        nanite_repair|Nanite Repair Pack|SUPPORT
        combat_stims|Combat Stimulants|SUPPORT
        neural_link|Neural Link|SUPPORT
        tactical_uplink|Tactical Uplink|SUPPORT
        sensor_web|Sensor Web|SUPPORT
        orbital_spotter|Orbital Spotter|SUPPORT
        teleport_beacon|Teleport Beacon|SUPPORT
        phase_harness|Phase Harness|SUPPORT
        grav_boots|Gravitic Boots|SUPPORT
        reactive_nanoplate|Reactive Nanoplate|SUPPORT
        ablative_lattice|Ablative Lattice|SUPPORT
        adaptive_camo|Adaptive Camouflage|SUPPORT
        orbital_strike|Orbital Strike|EXOTIC
        kinetic_bombardment|Kinetic Bombardment|RAIL
        ion_storm|Ion Storm Generator|EXOTIC
        plasma_deluge|Plasma Deluge|PLASMA
        beam_cascade|Beam Cascade|BEAM
        swarm_release|Swarm Release|DRONE
        siege_drill|Siege Drill|ARMOUR
        wall_dissolver|Wall Dissolver|EXOTIC
        foundation_shear|Foundation Shear|EXOTIC
        atmospheric_igniter|Atmospheric Igniter|EXOTIC
        graviton_press|Graviton Press|EXOTIC
        world_breaker|World Breaker|EXOTIC
        coil_pistol|Coil Pistol|RAIL
        coil_carbine|Coil Carbine|RAIL
        gauss_smg|Gauss SMG|RAIL
        gauss_marksman|Gauss Marksman Rifle|RAIL
        gauss_support|Gauss Support Weapon|RAIL
        rail_sidearm|Rail Sidearm|RAIL
        rail_carbine|Rail Carbine|RAIL
        rail_lance_heavy|Heavy Rail Lance|RAIL
        rail_battery_fixed|Fixed Rail Battery|RAIL
        rail_howitzer|Rail Howitzer|RAIL
        mass_accelerator|Mass Accelerator|RAIL
        slug_thrower|Slug Thrower|RAIL
        flechette_rail|Flechette Rail Gun|RAIL
        kinetic_penetrator|Kinetic Penetrator|RAIL
        orbital_rod|Orbital Rod|RAIL
        tungsten_lance|Tungsten Lance|RAIL
        laser_sidearm|Laser Sidearm|BEAM
        laser_smg|Laser SMG|BEAM
        laser_marksman|Laser Marksman Rifle|BEAM
        laser_support|Laser Support Weapon|BEAM
        pulse_carbine|Pulse Carbine|BEAM
        pulse_repeater|Pulse Repeater|BEAM
        beam_projector|Beam Projector|BEAM
        beam_cutter|Beam Cutter|BEAM
        heat_ray|Heat Ray|BEAM
        infrared_lance|Infrared Lance|BEAM
        ultraviolet_lance|Ultraviolet Lance|BEAM
        xray_laser|X-Ray Laser|BEAM
        gamma_laser|Gamma Laser|BEAM
        free_electron_laser|Free Electron Laser|BEAM
        chemical_laser|Chemical Laser|BEAM
        dazzler|Dazzler|SUPPORT
        blinding_array|Blinding Array|SUPPORT
        microwave_emitter|Microwave Emitter|BEAM
        maser_cannon|Maser Cannon|BEAM
        particle_lance|Particle Lance|BEAM
        neutron_beam|Neutron Beam|BEAM
        proton_beam|Proton Beam|BEAM
        positron_beam|Positron Beam|BEAM
        graviton_lance|Graviton Lance|BEAM
        tachyon_beam|Tachyon Beam|EXOTIC
        plasma_sidearm|Plasma Sidearm|PLASMA
        plasma_carbine|Plasma Carbine|PLASMA
        plasma_repeater|Plasma Repeater|PLASMA
        plasma_thrower|Plasma Thrower|PLASMA
        plasma_howitzer|Plasma Howitzer|PLASMA
        plasma_torpedo|Plasma Torpedo|PLASMA
        fusion_cutter|Fusion Cutter|PLASMA
        fusion_bolt|Fusion Bolt Gun|PLASMA
        arc_thrower|Arc Thrower|PLASMA
        tesla_coil_weapon|Tesla Coil|PLASMA
        lightning_gun|Lightning Gun|PLASMA
        ion_lance|Ion Lance|PLASMA
        ion_storm_projector|Ion Storm Projector|PLASMA
        magnetoplasma_cannon|Magnetoplasma Cannon|PLASMA
        thermobaric_plasma|Thermobaric Plasma Charge|PLASMA
        drone_scout_micro|Micro Scout Drone|DRONE
        drone_recon_stealth|Stealth Recon Drone|DRONE
        drone_strike_light|Light Strike Drone|DRONE
        drone_strike_heavy|Heavy Strike Drone|DRONE
        drone_bomber|Bomber Drone|DRONE
        drone_interceptor|Interceptor Drone|DRONE
        drone_suicide|Suicide Drone|DRONE
        drone_sapper|Sapper Drone|DRONE
        drone_swarm_small|Small Swarm|DRONE
        drone_swarm_large|Large Swarm|DRONE
        drone_carrier_mobile|Mobile Drone Carrier|DRONE
        drone_shepherd|Shepherd Drone|SUPPORT
        drone_repair|Repair Drone|SUPPORT
        drone_medic|Medic Drone|SUPPORT
        drone_supply|Supply Drone|SUPPORT
        drone_relay|Relay Drone|SUPPORT
        nanite_cloud|Nanite Cloud|DRONE
        nanite_disassembler|Nanite Disassembler|DRONE
        nanite_infiltrator|Nanite Infiltrator|DRONE
        grey_goo_canister|Grey Goo Canister|EXOTIC
        smart_dust|Smart Dust|SUPPORT
        exo_light|Light Exosuit|ARMOUR
        exo_assault|Assault Exosuit|ARMOUR
        exo_heavy|Heavy Exosuit|ARMOUR
        exo_siege|Siege Exosuit|ARMOUR
        exo_stealth|Stealth Exosuit|ARMOUR
        mech_scout|Scout Mech|ARMOUR
        mech_line|Line Mech|ARMOUR
        mech_assault|Assault Mech|ARMOUR
        mech_artillery|Artillery Mech|ARMOUR
        mech_command|Command Mech|SUPPORT
        walker_quad|Quadruped Walker|ARMOUR
        walker_hex|Hexapod Walker|ARMOUR
        walker_titan|Titan Walker|ARMOUR
        hover_scout|Hover Scout|ARMOUR
        hover_apc|Hover APC|ARMOUR
        hover_gun_platform|Hover Gun Platform|ARMOUR
        grav_lifter|Grav Lifter|SUPPORT
        grav_assault_sled|Grav Assault Sled|ARMOUR
        crawler_siege|Siege Crawler|ARMOUR
        burrower|Burrowing Assault Rig|ARMOUR
        aerospace_interceptor|Aerospace Interceptor|AIRCRAFT
        aerospace_strike|Aerospace Strike Craft|AIRCRAFT
        aerospace_bomber|Aerospace Bomber|AIRCRAFT
        dropship_assault|Assault Dropship|SUPPORT
        gunship_grav|Grav Gunship|AIRCRAFT
        stealth_lander|Stealth Lander|SUPPORT
        orbital_shuttle|Orbital Shuttle|SUPPORT
        carrier_frame|Carrier Frame|AIRCRAFT
        interface_craft|Interface Craft|AIRCRAFT
        atmospheric_skimmer|Atmospheric Skimmer|AIRCRAFT
        missile_smart|Smart Missile|MISSILE
        missile_swarm_pod|Swarm Missile Pod|MISSILE
        missile_hypersonic|Hypersonic Missile|MISSILE
        missile_hunter_killer|Hunter-Killer Missile|MISSILE
        missile_antimatter|Antimatter Missile|MISSILE
        missile_orbital|Orbital Missile|MISSILE
        missile_seeker_cluster|Seeker Cluster|MISSILE
        missile_loitering|Loitering Missile|MISSILE
        torpedo_void|Void Torpedo|EXOTIC
        torpedo_phase|Phase Torpedo|EXOTIC
        torpedo_singularity|Singularity Torpedo|EXOTIC
        charge_stasis|Stasis Charge|EXOTIC
        charge_entropy|Entropy Charge|EXOTIC
        charge_gravity|Gravity Charge|EXOTIC
        charge_vacuum|Vacuum Charge|EXOTIC
        charge_dimensional|Dimensional Charge|EXOTIC
        charge_temporal|Temporal Charge|EXOTIC
        charge_null|Null Charge|EXOTIC
        bomb_antimatter|Antimatter Bomb|EXOTIC
        bomb_neutron|Neutron Bomb|EXOTIC
        bomb_gravitic|Gravitic Bomb|EXOTIC
        bomb_planetcracker|Crust Breaker|EXOTIC
        beam_orbital_strike|Orbital Beam Strike|EXOTIC
        rod_from_god|Kinetic Bombardment|RAIL
        swarm_orbital|Orbital Swarm Release|DRONE
        weather_weapon|Weather Weapon|EXOTIC
        seismic_weapon|Seismic Weapon|EXOTIC
        atmospheric_burner|Atmospheric Burner|EXOTIC
        mono_blade|Monomolecular Blade|BLADE
        phase_blade|Phase Blade|BLADE
        plasma_blade|Plasma Blade|BLADE
        vibro_sword|Vibro Sword|BLADE
        vibro_glaive|Vibro Glaive|POLEARM
        arc_spear|Arc Spear|POLEARM
        grav_hammer|Grav Hammer|BLUNT
        shock_baton|Shock Baton|BLUNT
        kinetic_gauntlet|Kinetic Gauntlet|BLUNT
        breaching_ram_power|Powered Breaching Ram|BLUNT
        chain_blade|Chain Blade|BLADE
        molecular_saw|Molecular Saw|BLADE
        force_pike|Force Pike|POLEARM
        stun_lance|Stun Lance|POLEARM
        field_shield_personal|Personal Field Shield|SUPPORT
        hardlight_buckler|Hardlight Buckler|SUPPORT
        deflector_harness|Deflector Harness|SUPPORT
        ablative_plating|Ablative Plating|SUPPORT
        reactive_nanoweave|Reactive Nanoweave|SUPPORT
        powered_frame|Powered Frame|SUPPORT
        inertial_harness|Inertial Harness|SUPPORT
        grav_harness|Grav Harness|SUPPORT
        phase_cloak|Phase Cloak|SUPPORT
        adaptive_camouflage|Adaptive Camouflage|SUPPORT
        thermal_masking|Thermal Masking|SUPPORT
        sensor_ghost|Sensor Ghost Emitter|SUPPORT
        decoy_hologram|Holographic Decoy|SUPPORT
        emp_grenade|EMP Grenade|SUPPORT
        emp_lance|EMP Lance|SUPPORT
        disruptor_pulse|Disruptor Pulse|SUPPORT
        stasis_grenade|Stasis Grenade|SUPPORT
        gravity_grenade|Gravity Grenade|EXOTIC
        singularity_grenade|Singularity Grenade|EXOTIC
        nanite_grenade|Nanite Grenade|DRONE
        neural_disruptor|Neural Disruptor|SUPPORT
        psi_amplifier|Psionic Amplifier|SUPPORT
        mind_shield|Mind Shield|SUPPORT
        neural_uplink|Neural Uplink|SUPPORT
        tactical_lattice|Tactical Lattice|SUPPORT
        battle_ai_core|Battle AI Core|SUPPORT
        predictive_targeting|Predictive Targeting|SUPPORT
        swarm_coordinator|Swarm Coordinator|SUPPORT
        orbital_spotter_uplink|Orbital Spotter Uplink|SUPPORT
        quantum_comms|Quantum Comms|SUPPORT
        entangled_relay|Entangled Relay|SUPPORT
        teleport_pad_field|Field Teleport Pad|SUPPORT
        phase_anchor|Phase Anchor|SUPPORT
        gate_beacon|Gate Beacon|SUPPORT
        nanite_medkit|Nanite Medkit|SUPPORT
        regeneration_pod|Regeneration Pod|SUPPORT
        combat_stim_injector|Combat Stim Injector|SUPPORT
        cryo_stasis_pack|Cryo Stasis Pack|SUPPORT
        field_fabricator_kit|Field Fabricator Kit|SUPPORT
        matter_repair_kit|Matter Repair Kit|SUPPORT
        shield_recharger|Shield Recharger|SUPPORT
        power_cell_heavy|Heavy Power Cell|SUPPORT
        fusion_backpack|Fusion Backpack|SUPPORT
        antimatter_flask|Antimatter Flask|SUPPORT
    """.trimIndent()

    private fun parse(block: String, age: Era.Age, levels: IntRange): List<Weapon> {
        val lines = block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val span = (levels.last - levels.first).coerceAtLeast(1)
        return lines.mapIndexed { index, line ->
            val parts = line.split('|')
            require(parts.size == 3) { "malformed weapon line: $line" }
            val at = if (lines.size <= 1) levels.first
            else levels.first + (index.toLong() * span / (lines.size - 1)).toInt()
            Weapon(
                id = parts[0],
                name = parts[1],
                weaponClass = WeaponClass.valueOf(parts[2]),
                age = age,
                unlockLevel = at.coerceIn(levels),
            )
        }
    }

    val ALL: List<Weapon> by lazy {
        // Level 2 rather than 1: level 1 is the razed town hall and two training camps, and the
        // design says weapons research is what arrives after level 2.
        (parse(MEDIEVAL, Era.Age.MEDIEVAL, 2..Era.MEDIEVAL_END) +
            parse(MODERN, Era.Age.MODERN, (Era.MEDIEVAL_END + 1)..Era.MODERN_END) +
            parse(FUTURISTIC, Era.Age.FUTURISTIC, (Era.MODERN_END + 1)..Era.MAX_LEVEL))
            .sortedWith(compareBy({ it.unlockLevel }, { it.id }))
    }

    private val byId: Map<String, Weapon> by lazy { ALL.associateBy { it.id } }

    fun byId(id: String): Weapon? = byId[id]

    /** The strongest [Wmd] of each kind the given research unlocks, if any. */
    fun wmdsIn(researched: Set<String>): Map<Wmd, Weapon> =
        researched.mapNotNull { byId(it) }
            .mapNotNull { weapon -> weapon.wmd?.let { it to weapon } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.maxByOrNull { it.threat }!! }

    fun unlockedAt(level: Int): List<Weapon> = ALL.filter { it.unlockLevel <= level }

    fun newAt(level: Int): List<Weapon> = ALL.filter { it.unlockLevel == level }

    fun ofAge(age: Era.Age): List<Weapon> = ALL.filter { it.age == age }

    /** The opening weapon. Somebody has to hit the invaders with something. */
    val STARTER: Weapon by lazy { byId("wooden_club") ?: ALL.first() }

    /**
     * The best researched weapon a soldier of this base would carry, given a role.
     *
     * Soldiers are not individually equipped by the player — five hundred levels of inventory
     * management is a different game — so this picks for them: the strongest thing they have
     * researched that suits the job. [preferRanged] is what makes a garrison shoot back rather than
     * charging out of a bunker with a sword.
     */
    fun bestResearched(researched: Set<String>, preferRanged: Boolean): Weapon {
        val owned = researched.mapNotNull { byId[it] }.filter { it.weaponClass != WeaponClass.SUPPORT }
        if (owned.isEmpty()) return STARTER
        val pool = owned.filter { it.weaponClass.isRanged == preferRanged }.ifEmpty { owned }
        return pool.maxByOrNull { it.threat } ?: STARTER
    }
}
