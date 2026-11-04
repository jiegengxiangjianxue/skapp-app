package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FamilyAggregationTest {
    private fun spec(kind: String = "family_weighted_vote") = EngineSpecLoader.parse("""
        {"spec_version":"3.3.0","aggregation":{"kind":"$kind"},
         "weights":{"A":1,"B":1,"C":1},
         "engines":[{"id":"a","weight_key":"A","family":"momentum"},
                    {"id":"b","weight_key":"B","family":"momentum"},
                    {"id":"c","weight_key":"C","family":"trend"},
                    {"id":"x","weight_key":"X","family":"external"},
                    {"id":"y","weight_key":"Y","family":"external"}]}
    """.trimIndent())

    private fun vote(key: String, direction: Int, strength: Double = 1.0) =
        CanonicalEngine.Vote(key, direction, strength, "fixture")

    private fun aggregate(votes: List<CanonicalEngine.Vote>, kind: String = "family_weighted_vote",
                          weights: Map<String, Double> = spec().weights) =
        CanonicalEngine.aggregateVotes(votes, weights, spec(kind))

    @Test fun cancellingMomentumAndPositiveTrendScore75() {
        val votes = listOf(vote("A", 1), vote("B", -1), vote("C", 1))
        val result = assertNotNull(aggregate(votes))
        assertEquals(75.0, result.score)
        assertEquals(0.5, result.raw)
        assertEquals(1.0, result.resonance)
        assertEquals(listOf(2, 1, 0), listOf(result.bull, result.bear, result.neutral))
        assertEquals(listOf(1, 0, 1), listOf(result.familyBull, result.familyBear, result.familyNeutral))
        assertEquals(result, aggregate(votes + votes.first()))
        assertEquals(result, aggregate(votes + vote("A", -1)))
    }

    @Test fun opposedFamiliesScore50WhileLegacyReplays644() {
        val votes = listOf(vote("A", 1), vote("B", 1), vote("C", -1))
        val result = assertNotNull(aggregate(votes))
        assertEquals(50.0, result.score)
        assertEquals(0.0, result.raw)
        assertEquals(0.5, result.resonance)
        assertEquals(listOf(2, 1, 0), listOf(result.bull, result.bear, result.neutral))
        assertEquals(listOf(1, 1, 0), listOf(result.familyBull, result.familyBear, result.familyNeutral))
        val legacy = assertNotNull(aggregate(votes, "weighted_vote"))
        // 历史公式：base=1/3、resonance=2/3，得64.4；不能为错误手算值改回放算法。
        assertEquals(64.4, legacy.score)
        assertEquals(0, legacy.familyBull)
        assertEquals(4, assertNotNull(aggregate(votes + votes.first(), "weighted_vote")).votes.size)
    }

    @Test fun neutralFamiliesRemainInDenominator() {
        val result = assertNotNull(aggregate(listOf(vote("A", 0), vote("C", 1))))
        assertEquals(75.0, result.score)
        assertEquals(1, result.familyNeutral)
        val neutral = assertNotNull(aggregate(listOf(vote("A", 0), vote("C", 0))))
        assertEquals(50.0, neutral.score)
        assertEquals(0.0, neutral.resonance)
        assertEquals(2, neutral.familyNeutral)
    }

    @Test fun invalidVotesAndWeightsDoNotContaminateValidVotes() {
        val votes = listOf(vote("A", 1), vote("B", 2), vote("C", 1, Double.NaN),
            vote("D", -1, -0.1), vote("E", 1, 1.1), vote("F", 1, Double.POSITIVE_INFINITY),
            vote("Z", -1), vote("N", -1), vote("I", -1), vote("M", -1))
        val result = assertNotNull(aggregate(votes, weights = mapOf("A" to 1.0, "Z" to 0.0,
            "N" to Double.NaN, "I" to Double.POSITIVE_INFINITY, "M" to -1.0)))
        assertEquals(100.0, result.score)
        assertEquals(listOf(vote("A", 1)), result.votes)
        assertNull(aggregate(emptyList()))
        assertNull(aggregate(listOf(vote("A", 1)), weights = mapOf("A" to 0.0)))
        assertNull(aggregate(listOf(vote("A", 2), vote("A", 1))))
    }

    @Test fun externalAndUnmappedIndicatorsAreIndependentAndMissingWeightsTracked() {
        val result = assertNotNull(aggregate(listOf(vote("X", 1), vote("Y", -1),
            vote("U", 1), vote("V", -1)), weights = emptyMap()))
        assertEquals(50.0, result.score)
        assertEquals(listOf(2, 2, 0), listOf(result.familyBull, result.familyBear, result.familyNeutral))
        assertEquals(listOf("X", "Y", "U", "V"), result.missing)
    }

    @Test fun familyWeightUsesMaximumNotSumAndToleranceOnlyAffectsDirection() {
        val result = assertNotNull(aggregate(listOf(vote("A", 1), vote("B", -1), vote("C", 1)),
            weights = mapOf("A" to 3.0, "B" to 1.0, "C" to 2.0)))
        assertEquals(0.7, result.raw)
        assertEquals(85.0, result.score)
        val nearZero = assertNotNull(aggregate(listOf(vote("A", 1, 1e-13))))
        assertEquals(1, nearZero.familyNeutral)
        assertEquals(0.0, nearZero.resonance)
        assertEquals(1, nearZero.bull)
    }

    @Test fun defaultWeightProvenanceIsAdditiveAndLegacySpecsRemainReadable() {
        assertEquals(emptyList(), spec().defaultWeightKeys)
        val parsed = EngineSpecLoader.parse("""{"default_weight_keys":["flow","tape","book"]}""")
        assertEquals(listOf("flow", "tape", "book"), parsed.defaultWeightKeys)
        assertEquals("weighted_vote", parsed.aggregation.kind)
        assertEquals("3.3.0", EngineSpecLoader.BUNDLED_VERSION)
    }

    @Test fun largeFiniteWeightsStayFiniteAcrossFamilies() {
        val result = assertNotNull(aggregate(listOf(vote("A", 1), vote("B", 1), vote("C", 1)),
            weights = mapOf("A" to 1e308, "B" to 1e308, "C" to 1e308)))
        assertEquals(100.0, result.score)
        assertEquals(1.0, result.raw)
        assertEquals(2, result.familyBull)
    }

    @Test fun invalidLegacyStrengthIsRejectedLikeCloud() {
        assertNull(aggregate(listOf(vote("A", 1, 1.1)), "weighted_vote"))
    }

    @Test fun unknownKindIsRejected() {
        assertFailsWith<IllegalArgumentException> { spec("unknown_vote") }
    }
}
