package com.forge.workout.nutrition.repository

import com.forge.workout.nutrition.data.NutritionValidation

internal class FoodSearchTerms(query: String, limit: Int, offset: Int) {
    val term: String
    val match: String
    val alternate: String?

    init {
        NutritionValidation.page(limit, offset)
        NutritionValidation.text(query, 250, "Search", true)
        term = query.trim()
        val words = WORD.findAll(term).take(32).map { it.value }.toList()
        match = words.joinToString(" ", transform = ::ftsPrefix)
        alternate = alternateMatch(words)
    }
}

private val WORD = Regex("[\\p{L}\\p{N}]+")

// Only equivalences that name the same ingredient. Preparation terms remain separate.
private val SYNONYMS = listOf(
    "chickpea flour" to "besan",
    "finger millet" to "ragi",
    "flattened rice" to "poha",
    "okra" to "bhindi",
    "eggplant" to "baingan",
    "fenugreek" to "methi",
    "semolina" to "suji",
    "cumin" to "jeera",
    "cardamom" to "elaichi",
    "turmeric" to "haldi",
    "carom" to "ajwain",
    "idli" to "idly",
    "sambar" to "sambhar",
    "chapati" to "chapatti",
).flatMap { (english, indian) ->
    listOf(english.split(' ') to indian, indian.split(' ') to english)
}.sortedByDescending { (from, _) -> from.size }

// Keep every other token literal when expanding one synonym, with at most one extra FTS lookup.
private fun alternateMatch(words: List<String>): String? {
    for ((from, to) in SYNONYMS) {
        val start = (0..(words.size - from.size)).firstOrNull { index ->
            from.indices.all { words[index + it].equals(from[it], ignoreCase = true) }
        } ?: continue
        return buildList {
            addAll(words.take(start).map(::ftsPrefix))
            add(ftsPrefix(to))
            addAll(words.drop(start + from.size).map(::ftsPrefix))
        }.joinToString(" ")
    }
    return null
}

private fun ftsPrefix(word: String) = "\"$word*\""
