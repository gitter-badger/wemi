package wemi.util

import java.util.*

/**
 * Utilities for text or object matching.
 */
@Suppress("MemberVisibilityCanPrivate")
object MatchUtils {

    /**
     * Compute weighted levenshtein distance of [s1] and [s0].
     * This is the minimum cost that it would take to convert s0 to s1 using only
     * letter insertion (with [insertCost]), letter replacement ([replaceCost]) and letter deletion ([deleteCost]).
     *
     * @param s0 text user entered
     * @param s1 text of result
     */
    fun levenshteinDistance(s0: CharSequence, s1: CharSequence, insertCost: Int, replaceCost: Int, deleteCost: Int): Int {
        val len0 = s0.length + 1
        val len1 = s1.length + 1

        // the array of distances
        var cost = IntArray(len0)
        var newCost = IntArray(len0)

        // initial cost of skipping prefix in String s0
        for (i in 0 until len0)
            cost[i] = i * deleteCost

        // dynamically computing the array of distances

        // transformation cost for each letter in s1
        for (i1 in 1 until len1) {
            // initial cost of skipping prefix in String s1
            newCost[0] = i1 * insertCost

            // transformation cost for each letter in s0
            for (i0 in 1 until len0) {
                // matching current letters in both strings
                val match = if (s0[i0 - 1] == s1[i1 - 1]) 0 else replaceCost

                // computing cost for each transformation
                val itemInsertCost = cost[i0] + insertCost //          \/
                val itemReplaceCost = cost[i0 - 1] + match //          _|
                val itemDeleteCost = newCost[i0 - 1] + deleteCost //   >

                // keep minimum cost
                newCost[i0] = min(itemInsertCost, itemDeleteCost, itemReplaceCost)
            }

            // swap cost/newCost arrays
            val swap = cost
            cost = newCost
            newCost = swap
        }

        // the distance is the cost for transforming all letters in both strings
        return cost[len0 - 1]
    }

    private fun min(a: Int, b: Int, c: Int): Int {
        if (a <= b && a <= c) return a
        return if (b <= a && b <= c) b else c
    }

    fun contentEquals(a: CharSequence?, b: CharSequence?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        val length = a.length
        if (length != b.length) return false
        for (i in 0 until length) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    /**
     * Reified version of [match] for convenience.
     */
    inline fun <reified T> match(from: Array<T>, noinline toString: (T) -> CharSequence, target: CharSequence): Array<T> {
        return match(T::class.java, from, toString, target)
    }

    /**
     * Given the list of possibilities of [type] [T] in [from], which can be converted to CharSequence
     * using function [toString], return those, that user could have possibly meant, when he/she typed [target].
     *
     * This is just a heuristic, that is tuned to prefer laziness to typos, but handles typos as well.
     */
    fun <T> match(type: Class<T>, from: Array<T>, toString: (T) -> CharSequence, target: CharSequence): Array<T> {
        val BAD_SCORE = 1000
        var bestScore = BAD_SCORE

        var considerOnlyPerfectMatches = false
        val scores = IntArray(from.size)
        for (i in from.indices) {
            val item = from[i]
            val itemName = toString(item)
            if (considerOnlyPerfectMatches) {
                if (contentEquals(target, itemName)) {
                    scores[i] = 0
                } else {
                    scores[i] = BAD_SCORE
                }
            } else {
                val score = levenshteinDistance(target, itemName, 2, 9, 7)
                if (score < bestScore) {
                    bestScore = score
                }
                if (score == 0) {
                    //Perfect match, continue searching, there may be dupes
                    considerOnlyPerfectMatches = true
                }
                scores[i] = score
            }
        }

        val worstShownScore = if (considerOnlyPerfectMatches) bestScore else bestScore * 2

        val resultItems = ArrayList<MatchResultItem>()
        for (i in from.indices) {
            val score = scores[i]
            if (score <= worstShownScore) {
                resultItems.add(MatchResultItem(i, score))
            }
        }
        resultItems.sort()

        @Suppress("UNCHECKED_CAST")
        val result = java.lang.reflect.Array.newInstance(type, minOf(8, resultItems.size)) as Array<T>
        for (i in result.indices) {
            result[i] = from[resultItems[i].index]
        }
        return result
    }

    private class MatchResultItem(val index: Int, val score: Int) : Comparable<MatchResultItem> {

        override fun compareTo(other: MatchResultItem): Int {
            return score - other.score
        }
    }
}