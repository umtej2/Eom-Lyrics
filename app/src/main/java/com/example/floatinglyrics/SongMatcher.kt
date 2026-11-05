package com.example.floatinglyrics

import java.text.Normalizer
import java.util.Locale

internal object SongMatcher {
    private val features = Regex("""\s*[\[(]?\s*\b(?:feat|ft|featuring)(?:\.\s*|\s+)(.+)$""", RegexOption.IGNORE_CASE)
    private val bracketedFeatures = Regex("""[\[(]\s*(?:feat|ft|featuring)(?:\.\s*|\s+)[^\])]*[\])]""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s*[,、·＆/+]\s*|\s+(?:및|and|&|×|x)\s+""", RegexOption.IGNORE_CASE)
    private val brackets = Regex("""[\[(]([^\])]+)[\])]""")
    private val whitespace = Regex("\\s+")
    private val information = Regex("""^(?:(?:official\s*)?(?:music\s*)?(?:video|audio|lyrics?|visuali[sz]er)|가사|한글\s*자막|explicit)$""", RegexOption.IGNORE_CASE)
    private val versions = listOf(
        "live" to Regex("""\blive\b|라이브|ライブ""", RegexOption.IGNORE_CASE),
        "remix" to Regex("""\bremix\b|리믹스|リミックス""", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("""\bacoustic\b|어쿠스틱|アコースティック""", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("""\binstrumental\b|\bkaraoke\b|\(mr\)|반주""", RegexOption.IGNORE_CASE),
        "spedup" to Regex("""\bsped[ -]?up\b|\bnightcore\b""", RegexOption.IGNORE_CASE),
        "slowed" to Regex("""\bslowed\b""", RegexOption.IGNORE_CASE),
        "clean" to Regex("""\bclean(?:\s+version)?\b""", RegexOption.IGNORE_CASE),
        "remaster" to Regex("""\bremaster(?:ed)?\b""", RegexOption.IGNORE_CASE)
    )

    data class Profile(
        val song: Song,
        val titles: List<String>,
        val artists: List<String>,
        val titleKeys: Set<String>,
        val artistKeys: Set<String>,
        val versions: Set<String>
    )
    data class Match(val score: Int, val canSync: Boolean)

    fun profile(song: Song): Profile {
        val titles = titleVariants(song.title).toMutableList()
        val artists = artistNames(song.artist, song.title)
        val keys = artists.map(::artistKey).toMutableSet()
        // This recording is indexed under different members of the same collaboration.
        if (titles.any { normalize(it) == "whoismoke" } &&
            keys.any { it in setOf("yungeenace", "spinabenz", "fastmoneygoon", "whoppawitdachoppa") }
        ) keys.addAll(listOf("yungeenace", "spinabenz", "fastmoneygoon", "whoppawitdachoppa"))
        if ("aimyon" in keys) addAlias(titles, "マリーゴールド", "Marigold")
        if ("backnumber" in keys) addAlias(titles, "エメラルド", "Emerald")
        return Profile(song, titles.distinct(), artists, titles.map(::normalize).toSet(), keys, versionTags(song.title))
    }

    fun match(profile: Profile, title: String, artist: String, durationMs: Long? = null): Match? {
        if (profile.versions != versionTags(title)) return null
        val candidateArtists = artistNames(artist, title).map(::artistKey).toSet()
        val sharedArtists = candidateArtists.intersect(profile.artistKeys).size
        if (sharedArtists == 0) return null
        val candidateTitles = titleVariants(title).map(::normalize)
        val exact = candidateTitles.any { it in profile.titleKeys }
        val delta = if (durationMs != null && durationMs > 0 && profile.song.durationMs > 0)
            kotlin.math.abs(durationMs - profile.song.durationMs) else null
        // A one-character typo is accepted only with a matching artist and a near-identical duration.
        val sameMainArtist = artistKey(artistNames(artist).firstOrNull().orEmpty()) ==
            artistKey(profile.artists.firstOrNull().orEmpty())
        val typo = !exact && sameMainArtist && delta != null && delta <= 3000 && candidateTitles.any { candidate ->
            profile.titleKeys.any { requested -> requested.length >= 8 && oneEditApart(requested, candidate) }
        }
        if (!exact && !typo) return null
        if (delta != null && delta > 30000) return null
        val mainArtist = artistKey(artistNames(artist).firstOrNull().orEmpty())
        val requestedMain = artistKey(profile.artists.firstOrNull().orEmpty())
        val artistScore = if (mainArtist == requestedMain) 25 else 12
        val timingScore = when {
            delta == null -> 0
            delta <= 2000 -> 15
            delta <= 7000 -> 8
            else -> -10
        }
        return Match((if (exact) 60 else 45) + artistScore + minOf(sharedArtists, 3) + timingScore,
            delta == null || delta <= 7000)
    }

    fun cleanTitle(value: String): String {
        val canonical = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val withoutFeature = features.replace(bracketedFeatures.replace(canonical, ""), "")
            .trim().trimEnd('(', '[').trim()
        return brackets.replace(withoutFeature) { match ->
            if (information.matches(match.groupValues[1].trim())) "" else match.value
        }.replace(whitespace, " ").trim()
    }

    fun artistNames(value: String, title: String = ""): List<String> {
        val featured = features.find(title)?.groupValues?.get(1)?.trimEnd(')', ']').orEmpty()
        val names = (listOf(value) + value.split(separators) + featured.split(separators)).flatMap { name ->
            val parenthesis = brackets.find(name)
            listOf(name.trim(), name.substringBefore('(').trim(), parenthesis?.groupValues?.get(1).orEmpty())
        }.filter(String::isNotBlank).distinct()
        return if (normalize(value) == "씨잼블랙넛" || normalize(value) == "cjammblacknut")
            names + listOf("씨잼", "블랙넛") else names
    }

    fun artistKey(value: String): String = when (val key = normalize(value)) {
        "씨잼", "cjamm" -> "cjamm"
        "블랙넛", "blacknut" -> "blacknut"
        "비프리", "bfree" -> "bfree"
        "aimyon", "あいみょん", "아이묭" -> "aimyon"
        "backnumber", "백넘버" -> "backnumber"
        "iu", "아이유" -> "iu"
        "whoppawitthachoppa" -> "whoppawitdachoppa"
        else -> key
    }

    fun sameRecording(first: Song?, second: Song?): Boolean {
        if (first == null || second == null) return first == second
        if (first.videoId != null && second.videoId != null) return first.videoId == second.videoId
        val durationMatches = first.durationMs <= 0 || second.durationMs <= 0 ||
            kotlin.math.abs(first.durationMs - second.durationMs) <= 1500
        return durationMatches && normalize(cleanTitle(first.title)) == normalize(cleanTitle(second.title)) &&
            recordingArtistKey(first.artist) == recordingArtistKey(second.artist)
    }

    fun recordingKey(song: Song): String = listOf(
        normalize(cleanTitle(song.title)), recordingArtistKey(song.artist), ((song.durationMs + 500) / 1000).toString()
    ).joinToString("|")

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun recordingArtistKey(value: String): String = value.split(separators)
        .map { artistKey(it.substringBefore('(').trim()) }.filter(String::isNotBlank)
        .distinct().sorted().joinToString(",")

    private fun titleVariants(value: String): List<String> {
        val cleaned = cleanTitle(value)
        val alternatives = mutableListOf(cleaned)
        if (versionTags(cleaned).isEmpty()) {
            for (match in brackets.findAll(cleaned)) {
                val translated = match.groupValues[1].trim()
                val base = cleaned.replace(match.value, "").trim()
                val hasLatin = base.any { it in 'A'..'Z' || it in 'a'..'z' }
                val translationLatin = translated.any { it in 'A'..'Z' || it in 'a'..'z' }
                if (base.isNotBlank() && translated.length >= 2 && hasLatin != translationLatin) {
                    alternatives.add(base)
                    alternatives.add(translated)
                }
            }
            val bilingual = cleaned.split(" - ")
            if (bilingual.size == 2 && bilingual.all(String::isNotBlank)) {
                val scripts = bilingual.map { part -> part.any { it in 'A'..'Z' || it in 'a'..'z' } }
                if (scripts[0] != scripts[1]) alternatives.addAll(bilingual)
            }
        }
        return alternatives.filter(String::isNotBlank).distinct().sortedBy { it.length }
    }

    private fun versionTags(value: String): Set<String> = versions.filter { it.second.containsMatchIn(value) }
        .map { it.first }.toSet()

    private fun addAlias(titles: MutableList<String>, first: String, second: String) {
        if (titles.any { normalize(it) in setOf(normalize(first), normalize(second)) }) {
            titles.add(first)
            titles.add(second)
        }
    }

    private fun oneEditApart(first: String, second: String): Boolean {
        if (kotlin.math.abs(first.length - second.length) > 1) return false
        var a = 0
        var b = 0
        var edits = 0
        while (a < first.length && b < second.length) {
            if (first[a] == second[b]) { a++; b++; continue }
            if (++edits > 1) return false
            if (first.length >= second.length) a++
            if (second.length >= first.length) b++
        }
        return edits + (first.length - a) + (second.length - b) <= 1
    }
}
