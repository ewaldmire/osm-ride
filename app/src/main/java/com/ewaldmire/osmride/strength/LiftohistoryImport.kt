package com.ewaldmire.osmride.strength

import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses fosslift's real workout export format ("liftohistory" - see fosslift's
 * src/liftohistory/liftohistory.grammar, liftohistorySerializer.ts, liftohistoryDeserializer.ts),
 * the same text its own "Copy as Text" button produces and can re-import. This is deliberately
 * NOT the Mastodon/Bluesky social-share format (that's a rendered PNG image, not structured
 * data) - liftohistory is fosslift's actual structured interchange format, so osm-ride adapts to
 * it rather than asking fosslift to emit something invented for this integration.
 *
 * Example input:
 * ```
 * 2026-09-09 14:30:00 -07:00 / program: "Push Day" / dayName: "Chest" / week: 2 / dayInWeek: 1 / duration: 3600s / exercises: {
 *   Bench Press / 3x8 185lb, 1x6 185lb @8 / warmup: 1x10 95lb / target: 3x8-10 185lb+
 *   Pull-ups / 3x10, 1x8
 * }
 * ```
 *
 * This is a pragmatic subset parser, not a full reimplementation of fosslift's Lezer grammar -
 * osm-ride sums every *completed* set per exercise (total sets/reps/volume, plus the top set for
 * "best set" display), but warmup/target sections, RPE, timers, set labels, comments, and
 * multi-week program metadata beyond the workout's display name are read past but not otherwise
 * interpreted. Malformed or unrecognized input yields null/empty results rather than throwing - a
 * share that doesn't parse should silently no-op, not crash the app.
 */
object LiftohistoryImport {
    private val dateRegex = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))? ?(Z|[+-]\d{2}:\d{2})""",
    )
    private val setPartRegex = Regex("""^(\d+)x(\d+)(?:\|\d+)?\+?$""")
    private val weightRegex = Regex("""^[+-]?(\d+(?:\.\d+)?)(lb|kg)\+?$""")
    private val propertyRegex = Regex("""^[a-zA-Z_][0-9a-zA-Z_]*:.*""")
    private const val KG_TO_LB = 2.2046226218

    fun parse(text: String): ImportedStrengthWorkout? {
        var headerLine: String? = null
        val exerciseLines = mutableListOf<String>()
        var inBody = false

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            if (!inBody) {
                // The header line is whichever non-comment, non-blank line ends the metadata
                // section with "exercises: {" - everything before that (date, program/day/
                // duration fields) lives on this one line per the grammar.
                if (line.contains("exercises:") && line.endsWith("{")) {
                    headerLine = line
                    inBody = true
                }
                continue
            }
            if (line == "}") break
            exerciseLines.add(line)
        }

        val header = headerLine ?: return null
        val recordedAtEpochMillis = parseDateToEpochMillis(header) ?: return null
        // dayName only appears for multi-week programs; falls back to the (single-week or adhoc)
        // program name; adhoc sessions have neither, so the UI falls back to "Workout".
        val workoutName = extractQuoted(header, "dayName") ?: extractQuoted(header, "program")

        val exercises = exerciseLines.mapNotNull { parseExerciseLine(it) }
        if (exercises.isEmpty()) return null
        return ImportedStrengthWorkout(recordedAtEpochMillis, workoutName, exercises)
    }

    private fun parseDateToEpochMillis(text: String): Long? {
        val m = dateRegex.find(text) ?: return null
        val g = m.groupValues
        return try {
            val fracRaw = g[7]
            val nanos = if (fracRaw.isNotEmpty()) fracRaw.padEnd(9, '0').take(9).toInt() else 0
            val offsetRaw = g[8]
            val offset = if (offsetRaw == "Z") ZoneOffset.UTC else ZoneOffset.of(offsetRaw)
            OffsetDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].toInt(), nanos, offset,
            ).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractQuoted(text: String, key: String): String? =
        Regex("""$key:\s*"([^"]*)"""").find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** One line looks like `ExerciseName / <sections...>`, where each section after the name is
     * either the unlabeled completed-sets list or a "keyword: ..." property (warmup, target,
     * etc.) - the grammar doesn't fix their order, so this looks for the one section without a
     * keyword prefix rather than assuming a position. "/" never appears inside a token per the
     * grammar's NonSeparator definition, so a plain split is safe.
     *
     * Confirmed directly against fosslift's liftohistorySerializer.ts (not just the looser
     * grammar, which doesn't structurally forbid this): the unlabeled section is always exactly
     * the full completed working-set list, exactly once, with completed warmup sets always routed
     * through their own "warmup:" section instead - so summing every group in the unlabeled
     * section is a safe way to compute real training totals, not a section that could ever also
     * contain warmup or planned (target) sets. */
    private fun parseExerciseLine(line: String): StrengthExercise? {
        val segments = line.split("/").map { it.trim() }
        val name = segments.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        // fosslift's own grammar allows ExerciseSections (completed sets, "warmup:", "target:",
        // etc.) in any order - CompletedSets is just whichever section has no "keyword:" prefix.
        // Don't assume it's always the first section after the name; find it by shape instead, so
        // this keeps working if fosslift's serializer ever changes the order it emits in.
        val completedSection = segments.drop(1).firstOrNull { !propertyRegex.matches(it) } ?: return null

        val groups = completedSection.split(",").mapNotNull { parseSetGroup(it.trim()) }
        if (groups.isEmpty()) return null
        // "Top set" = heaviest weight logged, ties broken by more reps at that weight; bodyweight
        // exercises (no weight token at all) fall back to the highest-rep group.
        val topSet = groups.maxWith(compareBy({ it.weightLbs ?: -1.0 }, { it.reps }))

        return StrengthExercise(
            name = name,
            topSetReps = topSet.reps,
            topSetWeightLbs = topSet.weightLbs ?: 0.0,
            totalSets = groups.sumOf { it.count },
            totalReps = groups.sumOf { it.count * it.reps },
            totalVolumeLbs = groups.sumOf { it.count * it.reps * (it.weightLbs ?: 0.0) },
        )
    }

    /** [count] is the "Nx" multiplier - "3x8 185lb" is 3 separate sets of 8 reps each, not one. */
    private data class ParsedSet(val count: Int, val reps: Int, val weightLbs: Double?)

    private fun parseSetGroup(token: String): ParsedSet? {
        var count = 1
        var reps: Int? = null
        var weightLbs: Double? = null
        for (piece in token.split(Regex("\\s+"))) {
            if (piece.isEmpty()) continue
            setPartRegex.find(piece)?.let {
                count = it.groupValues[1].toInt()
                reps = it.groupValues[2].toInt()
            }
            // A leading +/- before the number is a different fosslift grammar feature (relative/
            // delta weight display) than the trailing "+" (target-only askWeight) below -
            // confirmed against their serializer that neither ever appears on a completed set, so
            // this doesn't need to special-case a sign here.
            weightRegex.find(piece)?.let { m ->
                val value = m.groupValues[1].toDouble()
                weightLbs = if (m.groupValues[2] == "kg") value * KG_TO_LB else value
            }
            // Rpe ("@N"), SetLabel ("(...)"), and Duration ("Ns") pieces are read past - not
            // needed for sets/reps/volume totals.
        }
        return reps?.let { ParsedSet(count, it, weightLbs) }
    }
}

data class ImportedStrengthWorkout(
    val recordedAtEpochMillis: Long,
    val workoutName: String?,
    val exercises: List<StrengthExercise>,
)
