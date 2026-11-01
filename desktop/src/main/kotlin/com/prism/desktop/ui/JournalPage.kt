package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.journal.Journal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal on the desktop: diary entries and the recipes kept beside them.
 *
 * The tab bar is the only navigation, exactly as on the phone. Editing is a state of this page
 * rather than a dialog, so a half-written entry cannot be dismissed by a stray click outside a
 * popup, and the list behind it stays one Back away.
 */
@Composable
fun JournalPage() {
    val colors = LocalPrismColors.current
    var tab by remember { mutableIntStateOf(0) }
    var editingEntry by remember { mutableStateOf<Journal.Entry?>(null) }
    var editingRecipe by remember { mutableStateOf<Journal.Recipe?>(null) }
    var composing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }

    val entries = remember(revision, query) { Journal.searchEntries(query) }
    val recipes = remember(revision, query) { Journal.searchRecipes(query) }

    PageScaffold(
        "Journal",
        if (composing) "Writing" else if (tab == 0) "${entries.size} entries" else "${recipes.size} recipes",
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("Diary", "Recipes").forEachIndexed { index, label ->
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (tab == index) colors.accent else colors.muted,
                        modifier = Modifier
                            .clickable {
                                tab = index
                                composing = false
                                editingEntry = null
                                editingRecipe = null
                            }
                            .padding(end = 18.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!composing) {
                    TextButton(onClick = {
                        composing = true
                        editingEntry = null
                        editingRecipe = null
                    }) { Text(if (tab == 0) "New entry" else "New recipe") }
                }
            }

            when {
                composing && tab == 0 -> EntryEditor(
                    entry = editingEntry,
                    onCancel = { composing = false; editingEntry = null },
                    onSaved = { composing = false; editingEntry = null; revision++ },
                )
                composing -> RecipeEditor(
                    recipe = editingRecipe,
                    onCancel = { composing = false; editingRecipe = null },
                    onSaved = { composing = false; editingRecipe = null; revision++ },
                )
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(if (tab == 0) "Search your diary" else "Search recipes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                    if (tab == 0) {
                        if (entries.isEmpty()) {
                            Text(
                                "Tap New entry to write today's entry. Entries are kept on this " +
                                    "device and included in a Time Machine snapshot.",
                                fontSize = 13.sp,
                                color = colors.muted,
                            )
                        }
                        for ((day, dayEntries) in entries.groupBy { it.day }.toSortedMap(compareByDescending { it })) {
                            Text(
                                prettyDay(day),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                            for (entry in dayEntries) {
                                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                editingEntry = entry
                                                composing = true
                                            }
                                            .padding(14.dp)
                                    ) {
                                        Text(entry.title.ifBlank { "Untitled" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            entry.body.lineSequence().firstOrNull().orEmpty().take(160) +
                                                if (entry.mood.isBlank()) "" else "  ·  ${entry.mood}",
                                            fontSize = 12.sp,
                                            color = colors.muted,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (recipes.isEmpty()) {
                            Text(
                                "Tap New recipe to write one. Ingredients and steps are one per line.",
                                fontSize = 13.sp,
                                color = colors.muted,
                            )
                        }
                        for (recipe in recipes) {
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editingRecipe = recipe
                                            composing = true
                                        }
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        (if (recipe.favourite) "★ " else "") + recipe.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${recipe.minutes} min · ${recipe.servings} servings · " +
                                            "${recipe.ingredients.size} ingredients",
                                        fontSize = 12.sp,
                                        color = colors.muted,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryEditor(
    entry: Journal.Entry?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var body by remember(entry?.id) { mutableStateOf(entry?.body.orEmpty()) }
    var mood by remember(entry?.id) { mutableStateOf(entry?.mood.orEmpty()) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(if (entry == null) "New entry" else "Edit entry", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("What happened today?") },
            modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 10.dp),
        )
        OutlinedTextField(
            value = mood,
            onValueChange = { mood = it },
            label = { Text("Mood") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 6.dp)) {
            for (option in Journal.MOODS) {
                TextButton(onClick = { mood = option }) { Text(option, fontSize = 12.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            if (entry != null) {
                TextButton(onClick = {
                    Journal.deleteEntry(entry.id)
                    onSaved()
                }) { Text("Delete", color = colors.destructive) }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    if (entry == null) Journal.addEntry(title, body, mood)
                    else Journal.updateEntry(entry.id, title, body, mood)
                    onSaved()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun RecipeEditor(
    recipe: Journal.Recipe?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var title by remember(recipe?.id) { mutableStateOf(recipe?.title.orEmpty()) }
    var ingredients by remember(recipe?.id) {
        mutableStateOf(recipe?.ingredients?.joinToString("\n").orEmpty())
    }
    var steps by remember(recipe?.id) {
        mutableStateOf(recipe?.steps?.joinToString("\n").orEmpty())
    }
    var minutes by remember(recipe?.id) { mutableStateOf((recipe?.minutes ?: 20).toString()) }
    var servings by remember(recipe?.id) { mutableStateOf((recipe?.servings ?: 2).toString()) }
    var tags by remember(recipe?.id) { mutableStateOf(recipe?.tags?.joinToString(", ").orEmpty()) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(if (recipe == null) "New recipe" else "Edit recipe", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Recipe title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        OutlinedTextField(
            value = ingredients,
            onValueChange = { ingredients = it },
            label = { Text("Ingredients, one per line") },
            modifier = Modifier.fillMaxWidth().height(130.dp).padding(top = 10.dp),
        )
        OutlinedTextField(
            value = steps,
            onValueChange = { steps = it },
            label = { Text("Method, one step per line") },
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit) },
                label = { Text("Minutes") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = servings,
                onValueChange = { servings = it.filter(Char::isDigit) },
                label = { Text("Servings") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text("Tags, comma separated") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            if (recipe != null) {
                TextButton(onClick = {
                    Journal.deleteRecipe(recipe.id)
                    onSaved()
                }) { Text("Delete", color = colors.destructive) }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val minutesValue = minutes.toIntOrNull() ?: 0
                    val servingsValue = servings.toIntOrNull() ?: 2
                    val ingredientList = ingredients.lines()
                    val stepList = steps.lines()
                    val tagList = tags.split(',').map { it.trim() }
                    if (recipe == null) {
                        Journal.addRecipe(title, ingredientList, stepList, minutesValue, servingsValue, tagList)
                    } else {
                        Journal.updateRecipe(
                            recipe.id, title, ingredientList, stepList,
                            minutesValue, servingsValue, tagList,
                        )
                    }
                    onSaved()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }
    }
}

private fun prettyDay(day: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) ?: return day
    SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(date)
}.getOrDefault(day)
