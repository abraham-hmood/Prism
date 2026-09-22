package com.prism.launcher.minigames

/**
 * How a country is governed, and what that does to it.
 *
 * ## What this is modelled on
 *
 * Victoria 3's law system, which is the best-known treatment of the idea in a game: a set of law
 * GROUPS, each holding several mutually exclusive LAWS, and a set of IDEOLOGIES that approve or
 * disapprove of particular laws. Enacting a law is a piece of research with a cost, and the laws
 * you have enacted decide how your country actually works.
 *
 * It is an adaptation, not a port. Victoria 3 runs laws against interest groups with political
 * strength, legitimacy and a passage timer; Paper Empire has none of those and would be a worse
 * game for gaining them. What carries over is the part that is interesting on a phone: a wide
 * space of mutually exclusive choices, each with a real mechanical effect, that together describe
 * a country rather than upgrade it.
 *
 * ## Why a country needs a university first
 *
 * Because the alternative is a level-3 hamlet with universal suffrage and a command economy, which
 * is funny once. Law research is gated on a standing university, which in this catalogue is a
 * late-medieval building — so the political game opens up roughly when a country has enough of an
 * economy for the choices to bite.
 *
 * ## The effects are deliberately few and legible
 *
 * Each law moves at most a handful of numbers: the civilian tax rate, build speed, research cost,
 * army capacity, defensive strength, and how quickly a conquered country settles down. A law with
 * fifteen modifiers is a law nobody can reason about, and the whole point of a choice is that the
 * player can see what it did.
 */
object Ideology {

    /** The three top-level tabs, as Victoria 3 groups them. */
    enum class Branch(val label: String) {
        POWER("Power Structure"),
        ECONOMY("Economy"),
        RIGHTS("Human Rights"),
    }

    /**
     * What a law changes.
     *
     * Multipliers where the thing is a rate and additions where it is a count, because that is how
     * each reads when two laws stack: two laws that each raise taxes by a tenth should compound,
     * and two that each add a builder should add two builders.
     */
    data class Effects(
        /** Multiplier on the XP civilians pay per minute. */
        val taxRate: Double = 1.0,
        /** Multiplier on construction time. Below one is faster. */
        val buildSpeed: Double = 1.0,
        /** Multiplier on what research costs. */
        val researchCost: Double = 1.0,
        /** Multiplier on how many soldiers the camps hold. */
        val armySize: Double = 1.0,
        /** Multiplier on what defensive buildings do. */
        val defence: Double = 1.0,
        /** Multiplier on population growth, and so on the tax base. */
        val populationGrowth: Double = 1.0,
        /** Multiplier on what an annexed country hands over. */
        val annexYield: Double = 1.0,
        /** Flat addition to the number of builders. */
        val builders: Int = 0,
    ) {
        /** Divides one set of effects by another. Used to measure a law against the baseline. */
        operator fun div(other: Effects): Effects = Effects(
            taxRate = taxRate / other.taxRate,
            buildSpeed = buildSpeed / other.buildSpeed,
            researchCost = researchCost / other.researchCost,
            armySize = armySize / other.armySize,
            defence = defence / other.defence,
            populationGrowth = populationGrowth / other.populationGrowth,
            annexYield = annexYield / other.annexYield,
            builders = builders - other.builders,
        )

        operator fun times(other: Effects): Effects = Effects(
            taxRate = taxRate * other.taxRate,
            buildSpeed = buildSpeed * other.buildSpeed,
            researchCost = researchCost * other.researchCost,
            armySize = armySize * other.armySize,
            defence = defence * other.defence,
            populationGrowth = populationGrowth * other.populationGrowth,
            annexYield = annexYield * other.annexYield,
            builders = builders + other.builders,
        )

        /** The short lines the UI shows under a law. Only what actually moved. */
        fun describe(): List<String> = buildList {
            fun pct(value: Double) = "${if (value >= 1) "+" else ""}${((value - 1) * 100).toInt()}%"
            if (taxRate != 1.0) add("Civilian taxes ${pct(taxRate)}")
            if (buildSpeed != 1.0) add("Build time ${pct(buildSpeed)}")
            if (researchCost != 1.0) add("Research cost ${pct(researchCost)}")
            if (armySize != 1.0) add("Army capacity ${pct(armySize)}")
            if (defence != 1.0) add("Defences ${pct(defence)}")
            if (populationGrowth != 1.0) add("Population growth ${pct(populationGrowth)}")
            if (annexYield != 1.0) add("Conquest yield ${pct(annexYield)}")
            if (builders != 0) add("${if (builders > 0) "+" else ""}$builders builders")
        }
    }

    /** One law: a single option inside a [Group]. */
    data class Law(
        val id: String,
        val name: String,
        val groupId: String,
        val summary: String,
        val effects: Effects,
        /** Town hall level before it can be enacted at all. */
        val minLevel: Int = 1,
        /** Multiplier on the group's base cost. A radical law costs more than a mild one. */
        val costFactor: Double = 1.0,
    ) {
        val group: Group? get() = groupOf(groupId)

        /** XP to enact. Scales with the level it needs, so late laws are late-game decisions. */
        val cost: Long get() = ((900 + minLevel * 140L) * costFactor).toLong()

        /** Seconds of deliberation. Long enough that a fast-forward is worth paying for. */
        val seconds: Int get() = (60 + minLevel * 3).coerceAtMost(900)
    }

    /** A set of mutually exclusive laws. A country has exactly one law from each group. */
    data class Group(
        val id: String,
        val name: String,
        val branch: Branch,
        val description: String,
    ) {
        val laws: List<Law> get() = ALL_LAWS.filter { it.groupId == id }
        /** What a country starts with: the first law in the group, always the most primitive. */
        val default: Law get() = laws.first()
    }

    // ── The groups ─────────────────────────────────────────────────────────

    val GROUPS: List<Group> = listOf(
        Group("governance", "Governance Principles", Branch.POWER,
            "The foundational authority by which the country is governed."),
        Group("power", "Distribution of Power", Branch.POWER,
            "Who gets a say, and how much of one."),
        Group("citizenship", "Citizenship", Branch.POWER,
            "Who counts as one of us."),
        Group("church", "Church and State", Branch.POWER,
            "What the state owes religion, and what religion owes the state."),
        Group("bureaucracy", "Bureaucracy", Branch.POWER,
            "How the administration is staffed."),
        Group("army", "Army Model", Branch.POWER,
            "How soldiers are raised and kept."),
        Group("security", "Internal Security", Branch.POWER,
            "What the state does about dissent at home."),

        Group("economy", "Economic System", Branch.ECONOMY,
            "The fundamental principles of the economy."),
        Group("trade", "Trade Policy", Branch.ECONOMY,
            "How goods cross the border."),
        Group("taxation", "Taxation", Branch.ECONOMY,
            "How revenue is collected."),
        Group("land", "Land Reform", Branch.ECONOMY,
            "Who owns the fields, and on what terms."),
        Group("policing", "Policing", Branch.ECONOMY,
            "How order is kept in the streets."),
        Group("education", "Education System", Branch.ECONOMY,
            "Who is taught, and by whom."),
        Group("health", "Health System", Branch.ECONOMY,
            "What happens when people fall ill."),

        Group("speech", "Free Speech", Branch.RIGHTS,
            "What may be said, printed and gathered for."),
        Group("labour", "Labour Rights", Branch.RIGHTS,
            "What an employer may require."),
        Group("children", "Children's Rights", Branch.RIGHTS,
            "Whether childhood is a thing the law recognises."),
        Group("women", "Rights of Women", Branch.RIGHTS,
            "What half the population is permitted to do."),
        Group("welfare", "Welfare", Branch.RIGHTS,
            "What the state owes the poor."),
        Group("migration", "Migration", Branch.RIGHTS,
            "Who may come and who may go."),
        Group("slavery", "Slavery", Branch.RIGHTS,
            "Whether a person may be property."),
        Group("associations", "Labour Associations", Branch.RIGHTS,
            "Whether workers may organise."),
        Group("warconduct", "Conduct of War", Branch.RIGHTS,
            "What an army is permitted to do to the people it finds."),
    )

    /** The law that stops an army killing the people it finds. See the "warconduct" group. */
    const val RULES_OF_WAR = "rules_of_war"

    /**
     * How likely a civilian caught in a battle is to be killed, for a country holding [laws].
     *
     * Zero once the rules of war are on the books; a third of that under customary restraint, which
     * is the point of having a middle rung; and the full rate for a country that has legislated
     * nothing, which is every country until it does.
     */
    fun civilianDeathRate(laws: Set<String>): Double = when {
        RULES_OF_WAR in laws -> 0.0
        "customary_restraint" in laws -> 0.04
        else -> 0.12
    }

    private val groupsById: Map<String, Group> by lazy { GROUPS.associateBy { it.id } }

    fun groupOf(id: String): Group? = groupsById[id]

    // ── The laws ───────────────────────────────────────────────────────────

    val ALL_LAWS: List<Law> = listOf(
        // -- Power structure --------------------------------------------------
        Law("monarchy", "Monarchy", "governance",
            "One ruler, by inheritance and by right.",
            Effects(buildSpeed = 0.94, taxRate = 0.95), minLevel = 1),
        Law("theocracy", "Theocracy", "governance",
            "The faith rules directly, and the ruler answers to it.",
            Effects(researchCost = 1.15, defence = 1.12, populationGrowth = 1.05), minLevel = 60),
        Law("presidential", "Presidential Republic", "governance",
            "An elected head of state with real power.",
            Effects(taxRate = 1.10, researchCost = 0.95), minLevel = 120),
        Law("parliamentary", "Parliamentary Republic", "governance",
            "A chamber governs and the executive answers to it.",
            Effects(taxRate = 1.14, buildSpeed = 0.96, armySize = 0.94), minLevel = 160),
        Law("council", "Council Republic", "governance",
            "Power sits with councils of workers.",
            Effects(buildSpeed = 0.82, taxRate = 1.20, armySize = 0.90), minLevel = 260),
        Law("technocracy", "Technocracy", "governance",
            "Those who understand the machine operate it.",
            Effects(researchCost = 0.72, taxRate = 1.05, populationGrowth = 0.95), minLevel = 330),

        Law("autocracy", "Autocracy", "power",
            "One will, unchecked.",
            Effects(armySize = 1.12, taxRate = 0.92, researchCost = 1.08), minLevel = 1),
        Law("oligarchy", "Oligarchy", "power",
            "A few families, by arrangement.",
            Effects(taxRate = 1.08, populationGrowth = 0.96), minLevel = 40),
        Law("landed_voting", "Landed Voting", "power",
            "A vote for those who hold land.",
            Effects(taxRate = 1.10, buildSpeed = 0.97), minLevel = 90),
        Law("wealth_voting", "Wealth Voting", "power",
            "A vote for those who have money.",
            Effects(taxRate = 1.18, populationGrowth = 0.94), minLevel = 140),
        Law("census_suffrage", "Census Suffrage", "power",
            "A vote for those who meet the threshold.",
            Effects(taxRate = 1.12, researchCost = 0.95), minLevel = 190),
        Law("universal_suffrage", "Universal Suffrage", "power",
            "A vote for everybody.",
            Effects(populationGrowth = 1.15, taxRate = 1.08, armySize = 0.95), minLevel = 240),
        Law("single_party", "Single-Party State", "power",
            "One party, and no other.",
            Effects(armySize = 1.20, buildSpeed = 0.88, populationGrowth = 0.90), minLevel = 270),

        Law("ethnostate", "Ethnostate", "citizenship",
            "One people, and nobody else.",
            Effects(populationGrowth = 0.82, armySize = 1.10, defence = 1.08), minLevel = 1),
        Law("national_supremacy", "National Supremacy", "citizenship",
            "Others may stay, in their place.",
            Effects(populationGrowth = 0.92, taxRate = 1.05), minLevel = 70),
        Law("cultural_exclusion", "Cultural Exclusion", "citizenship",
            "Join us and you are one of us.",
            Effects(populationGrowth = 1.06), minLevel = 150),
        Law("multicultural", "Multiculturalism", "citizenship",
            "Everyone here is from here.",
            Effects(populationGrowth = 1.22, researchCost = 0.93, armySize = 0.96), minLevel = 230),

        Law("state_religion", "State Religion", "church",
            "One faith, established by law.",
            Effects(defence = 1.08, researchCost = 1.10, populationGrowth = 1.04), minLevel = 1),
        Law("freedom_conscience", "Freedom of Conscience", "church",
            "Believe as you like; the state has a preference.",
            Effects(populationGrowth = 1.08, researchCost = 0.97), minLevel = 110),
        Law("total_separation", "Total Separation", "church",
            "The state has no religion at all.",
            Effects(researchCost = 0.90, populationGrowth = 1.05), minLevel = 200),
        Law("state_atheism", "State Atheism", "church",
            "The state has a position, and it is against.",
            Effects(researchCost = 0.86, populationGrowth = 0.94, taxRate = 1.06), minLevel = 290),

        Law("hereditary_bureaucrats", "Hereditary Bureaucrats", "bureaucracy",
            "The post passes to the son.",
            Effects(taxRate = 0.92, buildSpeed = 1.05), minLevel = 1),
        Law("appointed_bureaucrats", "Appointed Bureaucrats", "bureaucracy",
            "The post goes to whoever is chosen for it.",
            Effects(taxRate = 1.05, buildSpeed = 0.96), minLevel = 80),
        Law("elected_bureaucrats", "Elected Bureaucrats", "bureaucracy",
            "The post goes to whoever wins it.",
            Effects(taxRate = 1.12, buildSpeed = 0.93, builders = 1), minLevel = 180),

        Law("peasant_levies", "Peasant Levies", "army",
            "Men called from the fields when needed.",
            Effects(armySize = 1.15, taxRate = 0.94, defence = 0.92), minLevel = 1),
        Law("national_militia", "National Militia", "army",
            "Every district keeps its own company.",
            Effects(defence = 1.20, armySize = 1.05), minLevel = 70),
        Law("professional_army", "Professional Army", "army",
            "Soldiers who do nothing else.",
            Effects(armySize = 0.92, defence = 1.15, taxRate = 0.95), minLevel = 140),
        Law("mass_conscription", "Mass Conscription", "army",
            "Everyone serves.",
            Effects(armySize = 1.45, populationGrowth = 0.92, taxRate = 0.90), minLevel = 210),

        Law("no_home_affairs", "No Home Affairs", "security",
            "The state does not watch its own.",
            Effects(populationGrowth = 1.06, taxRate = 0.96), minLevel = 1),
        Law("national_guard", "National Guard", "security",
            "A force for order, in uniform and in daylight.",
            Effects(defence = 1.12, taxRate = 1.04), minLevel = 100),
        Law("secret_police", "Secret Police", "security",
            "A force for order, in neither.",
            Effects(defence = 1.18, populationGrowth = 0.93, annexYield = 1.15), minLevel = 190),

        // -- Economy ----------------------------------------------------------
        Law("traditionalism", "Traditionalism", "economy",
            "Things are made the way they have always been made.",
            Effects(buildSpeed = 1.08, taxRate = 0.90, researchCost = 1.10), minLevel = 1),
        Law("interventionism", "Interventionism", "economy",
            "The state has opinions about industry.",
            Effects(buildSpeed = 0.95, taxRate = 1.08), minLevel = 90),
        Law("agrarianism", "Agrarianism", "economy",
            "The land comes first.",
            Effects(populationGrowth = 1.14, taxRate = 1.02, researchCost = 1.06), minLevel = 60),
        Law("laissez_faire", "Laissez-Faire", "economy",
            "The market decides, and the state stands back.",
            Effects(buildSpeed = 0.88, taxRate = 1.15, populationGrowth = 0.95), minLevel = 170),
        Law("command_economy", "Command Economy", "economy",
            "Everything is planned, including the plan.",
            Effects(buildSpeed = 0.78, armySize = 1.15, taxRate = 0.95, researchCost = 1.05), minLevel = 250),
        Law("cooperative_ownership", "Cooperative Ownership", "economy",
            "The people who work it own it.",
            Effects(taxRate = 1.22, populationGrowth = 1.10, armySize = 0.92), minLevel = 300),

        Law("mercantilism", "Mercantilism", "trade",
            "Export much, import little.",
            Effects(taxRate = 1.06, buildSpeed = 0.98), minLevel = 1),
        Law("protectionism", "Protectionism", "trade",
            "Our industry, protected.",
            Effects(buildSpeed = 0.94, taxRate = 1.02), minLevel = 110),
        Law("free_trade", "Free Trade", "trade",
            "Let it all move.",
            Effects(taxRate = 1.16, researchCost = 0.94, defence = 0.95), minLevel = 190),
        Law("isolationism", "Isolationism", "trade",
            "Nothing crosses the border in either direction.",
            Effects(defence = 1.15, taxRate = 0.88, researchCost = 1.10), minLevel = 130),

        Law("consumption_tax", "Consumption-Based Taxation", "taxation",
            "Tax what people buy.",
            Effects(taxRate = 1.05, populationGrowth = 0.98), minLevel = 1),
        Law("land_tax", "Land-Based Taxation", "taxation",
            "Tax what people hold.",
            Effects(taxRate = 1.10), minLevel = 50),
        Law("per_capita_tax", "Per-Capita Taxation", "taxation",
            "Tax each head equally.",
            Effects(taxRate = 1.18, populationGrowth = 0.94), minLevel = 120),
        Law("proportional_tax", "Proportional Taxation", "taxation",
            "Tax each purse the same share.",
            Effects(taxRate = 1.24, populationGrowth = 0.99), minLevel = 200),
        Law("graduated_tax", "Graduated Taxation", "taxation",
            "Tax the larger purse harder.",
            Effects(taxRate = 1.32, populationGrowth = 1.04, buildSpeed = 1.03), minLevel = 280),

        Law("serfdom", "Serfdom", "land",
            "The farmer belongs to the field.",
            Effects(taxRate = 1.08, populationGrowth = 0.90, buildSpeed = 0.96), minLevel = 1),
        Law("tenant_farmers", "Tenant Farmers", "land",
            "The farmer rents the field.",
            Effects(taxRate = 1.06, populationGrowth = 1.04), minLevel = 80),
        Law("commercial_agriculture", "Commercialised Agriculture", "land",
            "The field is a business.",
            Effects(taxRate = 1.14, populationGrowth = 1.02), minLevel = 160),
        Law("homesteading", "Homesteading", "land",
            "The farmer owns the field.",
            Effects(populationGrowth = 1.18, taxRate = 1.04), minLevel = 220),
        Law("collectivised", "Collectivised Agriculture", "land",
            "Everyone owns every field.",
            Effects(taxRate = 1.16, buildSpeed = 0.92, populationGrowth = 0.96), minLevel = 290),

        Law("no_police", "No Police", "policing",
            "Order is a private matter.",
            Effects(taxRate = 0.94, defence = 0.94), minLevel = 1),
        Law("local_police", "Local Police Force", "policing",
            "Each district keeps its own.",
            Effects(defence = 1.08, taxRate = 1.02), minLevel = 60),
        Law("dedicated_police", "Dedicated Police", "policing",
            "A force answering to the state.",
            Effects(defence = 1.14, taxRate = 1.05), minLevel = 150),
        Law("militarised_police", "Militarised Police", "policing",
            "A force answering to the state, armed like an army.",
            Effects(defence = 1.24, populationGrowth = 0.94, annexYield = 1.12), minLevel = 240),

        Law("no_schools", "No Schools", "education",
            "Learning is what parents are for.",
            Effects(researchCost = 1.25, taxRate = 0.96), minLevel = 1),
        Law("religious_schools", "Religious Schools", "education",
            "The faith teaches the children.",
            Effects(researchCost = 1.05, populationGrowth = 1.04), minLevel = 40),
        Law("private_schools", "Private Schools", "education",
            "Those who can pay, learn.",
            Effects(researchCost = 0.92, taxRate = 1.04), minLevel = 120),
        Law("public_schools", "Public Schools", "education",
            "Everyone learns.",
            Effects(researchCost = 0.78, populationGrowth = 1.08, taxRate = 0.96), minLevel = 210),

        Law("no_health", "No Health System", "health",
            "You get better or you do not.",
            Effects(populationGrowth = 0.90, taxRate = 1.02), minLevel = 1),
        Law("charity_hospitals", "Charity Hospitals", "health",
            "The devout keep a ward.",
            Effects(populationGrowth = 1.02), minLevel = 50),
        Law("private_health", "Private Health Insurance", "health",
            "Cover, for those who buy it.",
            Effects(populationGrowth = 1.08, taxRate = 1.04), minLevel = 160),
        Law("public_health", "Public Health Insurance", "health",
            "Cover, for everybody.",
            Effects(populationGrowth = 1.20, taxRate = 0.94), minLevel = 250),

        // -- Human rights -----------------------------------------------------
        Law("outlawed_dissent", "Outlawed Dissent", "speech",
            "Disagreement is a crime.",
            Effects(defence = 1.14, researchCost = 1.18, populationGrowth = 0.92), minLevel = 1),
        Law("censorship", "Censorship", "speech",
            "Disagreement is permitted quietly.",
            Effects(researchCost = 1.06, defence = 1.05), minLevel = 70),
        Law("right_of_assembly", "Right of Assembly", "speech",
            "People may gather and say so.",
            Effects(researchCost = 0.94, populationGrowth = 1.06), minLevel = 150),
        Law("protected_speech", "Protected Speech", "speech",
            "Saying it is a right the state may not touch.",
            Effects(researchCost = 0.84, populationGrowth = 1.10, defence = 0.96), minLevel = 230),

        Law("no_workers_rights", "No Workers' Rights", "labour",
            "The bargain is whatever was agreed.",
            Effects(taxRate = 1.10, populationGrowth = 0.92), minLevel = 1),
        Law("regulatory_bodies", "Regulatory Bodies", "labour",
            "Inspectors, and rules for them to enforce.",
            Effects(taxRate = 1.02, populationGrowth = 1.04), minLevel = 130),
        Law("worker_protections", "Workers' Protections", "labour",
            "Hours, safety and a floor under wages.",
            Effects(populationGrowth = 1.12, taxRate = 0.96, buildSpeed = 1.04), minLevel = 220),

        Law("child_labour", "Child Labour Allowed", "children",
            "Small hands reach further into the machine.",
            Effects(buildSpeed = 0.92, populationGrowth = 0.88, researchCost = 1.12), minLevel = 1),
        Law("restricted_child_labour", "Restricted Child Labour", "children",
            "Not below an age, and not in the worst of it.",
            Effects(populationGrowth = 1.02, researchCost = 1.02), minLevel = 110),
        Law("compulsory_school", "Compulsory Primary School", "children",
            "Childhood is for school.",
            Effects(researchCost = 0.86, populationGrowth = 1.10, buildSpeed = 1.03), minLevel = 200),

        Law("legal_guardianship", "Legal Guardianship", "women",
            "A woman answers to a man in law.",
            Effects(populationGrowth = 0.94, researchCost = 1.10), minLevel = 1),
        Law("women_own_property", "Women Own Property", "women",
            "What she holds is hers.",
            Effects(taxRate = 1.06, populationGrowth = 1.02), minLevel = 120),
        Law("women_in_workplace", "Women in the Workplace", "women",
            "Half the workforce, counted.",
            Effects(buildSpeed = 0.90, taxRate = 1.10, researchCost = 0.94), minLevel = 190),
        Law("womens_suffrage", "Women's Suffrage", "women",
            "Half the electorate, counted.",
            Effects(researchCost = 0.88, populationGrowth = 1.10, taxRate = 1.06), minLevel = 260),

        Law("no_social_security", "No Social Security", "welfare",
            "The poor are somebody else's business.",
            Effects(taxRate = 1.06, populationGrowth = 0.94), minLevel = 1),
        Law("poor_laws", "Poor Laws", "welfare",
            "A workhouse, and rules about who may enter it.",
            Effects(populationGrowth = 1.02), minLevel = 90),
        Law("wage_subsidies", "Wage Subsidies", "welfare",
            "The state tops up what work pays.",
            Effects(populationGrowth = 1.08, taxRate = 0.96), minLevel = 190),
        Law("old_age_pension", "Old Age Pension", "welfare",
            "A living after the working life.",
            Effects(populationGrowth = 1.16, taxRate = 0.92), minLevel = 270),

        Law("no_migration_controls", "No Migration Controls", "migration",
            "Anyone may come.",
            Effects(populationGrowth = 1.16, defence = 0.95), minLevel = 1),
        Law("migration_controls", "Migration Controls", "migration",
            "Some may come, on terms.",
            Effects(populationGrowth = 1.02, defence = 1.04), minLevel = 100),
        Law("closed_borders", "Closed Borders", "migration",
            "Nobody comes.",
            Effects(populationGrowth = 0.86, defence = 1.14), minLevel = 180),

        Law("slavery_banned", "Slavery Banned", "slavery",
            "A person may not be property.",
            Effects(populationGrowth = 1.10, taxRate = 1.02), minLevel = 1),
        Law("debt_slavery", "Debt Slavery", "slavery",
            "A debt may be paid in years.",
            Effects(buildSpeed = 0.90, populationGrowth = 0.88, taxRate = 1.08), minLevel = 30),
        Law("legacy_slavery", "Legacy Slavery", "slavery",
            "Born to it, and inherited.",
            Effects(buildSpeed = 0.84, populationGrowth = 0.80, taxRate = 1.14, researchCost = 1.15), minLevel = 60),

        // ── Conduct of war ──────────────────────────────────────────────
        //
        // The only law group whose effect is on somebody ELSE'S country. Its point is not the
        // modifiers, which are deliberately small: a country that binds its army's hands pays for
        // it slightly in the field and stops killing civilians, in its own country and in the ones
        // it invades. Everything is at "no quarter" until it is legislated otherwise, because that
        // is where every country in history started.
        Law("no_quarter", "No Quarter", "warconduct",
            "Whatever is in the way is in the way.",
            Effects(armySize = 1.06, populationGrowth = 0.97), minLevel = 1),
        Law("customary_restraint", "Customary Restraint", "warconduct",
            "Custom, and a commander's conscience. Better than nothing, and about as reliable.",
            Effects(armySize = 1.02, populationGrowth = 1.02), minLevel = 40),
        Law("rules_of_war", "Rules of War", "warconduct",
            "Written down, taught at the university, and binding on every officer: the people who " +
                "live somewhere are not part of the war being fought over it.",
            Effects(armySize = 0.97, populationGrowth = 1.06, taxRate = 1.03), minLevel = 65),

        Law("associations_illegal", "Associations Illegal", "associations",
            "Organising is a conspiracy.",
            Effects(taxRate = 1.08, populationGrowth = 0.94, buildSpeed = 0.96), minLevel = 1),
        Law("corporatised_unions", "Corporatised Unions", "associations",
            "Unions, licensed by the state.",
            Effects(taxRate = 1.04, buildSpeed = 0.98, populationGrowth = 1.02), minLevel = 140),
        Law("free_trade_unions", "Free Trade Unions", "associations",
            "Workers organise as they please.",
            Effects(populationGrowth = 1.10, taxRate = 0.98, buildSpeed = 1.04), minLevel = 230),
    )

    private val lawsById: Map<String, Law> by lazy { ALL_LAWS.associateBy { it.id } }

    fun law(id: String): Law? = lawsById[id]

    /** The law a country has in [groupId], falling back to that group's starting law. */
    fun lawIn(enacted: Set<String>, groupId: String): Law? {
        val group = groupOf(groupId) ?: return null
        return group.laws.lastOrNull { it.id in enacted } ?: group.default
    }

    /**
     * What a country's starting laws do, before it has enacted anything.
     *
     * Every group has a law in force from the first moment — serfdom, peasant levies, no schools —
     * and each of those carries its own effects, because they describe a real condition rather than
     * an absence of one. Multiplied together they came to a large silent bonus: a brand-new country
     * had a 40% larger army than the caps said it should, and weapons research cost twice what the
     * catalogue printed.
     */
    private val baseline: Effects by lazy {
        GROUPS.fold(Effects()) { total, group -> total * group.default.effects }
    }

    /**
     * Everything a country's whole body of law does to it, relative to a country that has enacted
     * nothing.
     *
     * Measured against [baseline] rather than against one, so an untouched country is exactly
     * neutral and every number the rest of the game prints — army capacity, research cost, build
     * time — means what it says until the player changes a law. It also means the authored numbers
     * on each law stay readable as absolute descriptions of that law, and adding a new group never
     * silently shifts the whole game.
     */
    fun effectsOf(enacted: Set<String>): Effects {
        val total = GROUPS.fold(Effects()) { acc, group ->
            acc * (lawIn(enacted, group.id)?.effects ?: Effects())
        }
        return total / baseline
    }

    /** What a country may enact next: one level above its current law in each group. */
    fun available(enacted: Set<String>, level: Int): List<Law> =
        ALL_LAWS.filter { it.id !in enacted && it.minLevel <= level }

    // ── Ideologies ─────────────────────────────────────────────────────────

    /**
     * An ideology: a name, a sentence, and a set of laws it likes or hates.
     *
     * These do not vote — there are no interest groups here to do the voting. What they are for is
     * NAMING what a country has become. A player who has enacted universal suffrage, public
     * schools and workers' protections is told they are running a Liberal Egalitarian state, which
     * is a far more interesting readout than a list of twenty-two enacted laws.
     */
    data class Stance(
        val id: String,
        val name: String,
        val summary: String,
        val loves: Set<String>,
        val hates: Set<String>,
    ) {
        /** How well a body of law matches this ideology, -1 to 1. */
        fun alignment(enacted: Set<String>): Double {
            val liked = loves.count { it in enacted }
            val disliked = hates.count { it in enacted }
            val total = (loves.size + hates.size).coerceAtLeast(1)
            return (liked - disliked).toDouble() / total
        }
    }

    val IDEOLOGIES: List<Stance> = listOf(
        Stance("agrarian", "Agrarian", "Policy should be about the land first.",
            loves = setOf("agrarianism", "traditionalism", "homesteading", "tenant_farmers"),
            hates = setOf("laissez_faire", "command_economy", "cooperative_ownership")),
        Stance("anticlerical", "Anti-Clerical", "Organised religion should keep out of the state.",
            loves = setOf("total_separation", "public_schools", "freedom_conscience", "state_atheism"),
            hates = setOf("state_religion", "religious_schools", "theocracy")),
        Stance("antislavery", "Abolitionist", "Owning a person is barbarous.",
            loves = setOf("slavery_banned", "homesteading"),
            hates = setOf("debt_slavery", "legacy_slavery", "serfdom")),
        Stance("egalitarian", "Egalitarian", "Equal and humane treatment, as a first principle.",
            loves = setOf("compulsory_school", "protected_speech", "worker_protections", "womens_suffrage"),
            hates = setOf("child_labour", "outlawed_dissent", "legal_guardianship")),
        Stance("hierarchic", "Hierarchic", "The old order held for a reason.",
            loves = setOf("serfdom", "peasant_levies", "legal_guardianship", "monarchy"),
            hates = setOf("universal_suffrage", "multicultural")),
        Stance("individualist", "Individualist", "Each person is responsible for their own fate.",
            loves = setOf("no_schools", "private_health", "no_health", "laissez_faire"),
            hates = setOf("public_schools", "public_health", "command_economy")),
        Stance("isolationist", "Isolationist", "Look inward; the world is trouble.",
            loves = setOf("closed_borders", "isolationism"),
            hates = setOf("no_migration_controls", "free_trade")),
        Stance("jingoist", "Jingoist", "A strong army, used often.",
            loves = setOf("mass_conscription", "militarised_police", "secret_police"),
            hates = setOf("protected_speech", "worker_protections")),
        Stance("laissezfaire", "Laissez-Faire", "Markets over government, everywhere.",
            loves = setOf("free_trade", "laissez_faire", "child_labour", "no_workers_rights"),
            hates = setOf("cooperative_ownership", "command_economy", "collectivised")),
        Stance("liberal", "Liberal", "Liberty and legal equality are what a state is for.",
            loves = setOf("protected_speech", "right_of_assembly", "census_suffrage", "parliamentary"),
            hates = setOf("outlawed_dissent", "secret_police", "single_party")),
        Stance("meritocratic", "Meritocratic", "Responsibility should be earned.",
            loves = setOf("elected_bureaucrats", "universal_suffrage", "technocracy", "public_schools"),
            hates = setOf("hereditary_bureaucrats", "serfdom")),
        Stance("moralist", "Moralist", "A religious moral order holds society together.",
            loves = setOf("state_religion", "theocracy", "religious_schools"),
            hates = setOf("total_separation", "freedom_conscience", "state_atheism")),
        Stance("paternalistic", "Paternalistic", "The many need guidance from the few.",
            loves = setOf("autocracy", "hereditary_bureaucrats", "monarchy", "poor_laws"),
            hates = setOf("elected_bureaucrats", "universal_suffrage")),
        Stance("patriotic", "Patriotic", "The country first, and harshly if need be.",
            loves = setOf("outlawed_dissent", "secret_police", "national_guard", "militarised_police"),
            hates = setOf("protected_speech", "no_migration_controls")),
        Stance("plutocratic", "Plutocratic", "Those who have built wealth have proved something.",
            loves = setOf("wealth_voting", "oligarchy", "landed_voting", "private_schools"),
            hates = setOf("universal_suffrage", "graduated_tax")),
        Stance("populist", "Populist", "Government by the people, all of them.",
            loves = setOf("universal_suffrage", "mass_conscription", "graduated_tax"),
            hates = setOf("oligarchy", "autocracy", "wealth_voting")),
        Stance("proletarian", "Proletarian", "Politics is the struggle between classes.",
            loves = setOf("cooperative_ownership", "command_economy", "graduated_tax", "free_trade_unions"),
            hates = setOf("laissez_faire", "traditionalism", "no_workers_rights")),
        Stance("reactionary", "Reactionary", "It was better before, and could be again.",
            loves = setOf("ethnostate", "monarchy", "serfdom", "state_religion"),
            hates = setOf("multicultural", "universal_suffrage")),
        Stance("republican", "Republican", "Leaders should be chosen by vote.",
            loves = setOf("parliamentary", "presidential", "universal_suffrage", "census_suffrage"),
            hates = setOf("autocracy", "oligarchy", "monarchy")),
        Stance("corporatist", "Corporatist", "Class conflict is resolved from above.",
            loves = setOf("corporatised_unions", "religious_schools", "single_party"),
            hates = setOf("free_trade_unions", "public_schools")),
        Stance("technocratic", "Technocratic", "The competent should run the machinery.",
            loves = setOf("technocracy", "public_schools", "elected_bureaucrats"),
            hates = setOf("no_schools", "hereditary_bureaucrats")),
    )

    /**
     * What to call a country, given the laws it has enacted.
     *
     * The best-matching ideology, or "Unformed" when nothing has been enacted at all — which is
     * honest rather than picking whichever ideology happens to like the default laws best.
     */
    fun describeCountry(enacted: Set<String>): String {
        if (enacted.isEmpty()) return "Unformed"
        val best = IDEOLOGIES.maxByOrNull { it.alignment(enacted) } ?: return "Unformed"
        val score = best.alignment(enacted)
        return when {
            score <= 0.0 -> "Undefined"
            score < 0.34 -> "Leaning ${best.name}"
            score < 0.67 -> best.name
            else -> "Firmly ${best.name}"
        }
    }

    /** The ideologies a country's laws please, best first. For the readout. */
    fun rank(enacted: Set<String>): List<Pair<Stance, Double>> =
        IDEOLOGIES.map { it to it.alignment(enacted) }
            .filter { it.second != 0.0 }
            .sortedByDescending { it.second }
}
