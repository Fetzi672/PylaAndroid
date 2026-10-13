package com.pyla.ai.engine
import org.junit.Assert.*
import org.junit.Test
class BotEngineRecoveryTest {
    @Test fun freshPlayMatchAllowsObservationRecovery() { assertTrue(BotEngine.staleRecoveryAuthorized("match", 4000L, "unknown", 5000L)) }
    @Test fun startupNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized(null, 4000L, "unknown", 5000L)) }
    @Test fun unverifiedUnknownNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized("unknown", 4000L, "unknown", 5000L)) }
    @Test fun previousMenuNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized("lobby", 4000L, "unknown", 5000L)) }
    @Test fun everyKnownCurrentMenuRevokesRecovery() {
        for (state in listOf("lobby", "shop", "popup", "end_victory", "match_making", "brawler_selection"))
            assertFalse(BotEngine.staleRecoveryAuthorized("match", 4000L, state, 5000L))
    }
    @Test fun missingTimestampNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized("match", 0L, "unknown", 5000L)) }
    @Test fun futureTimestampNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized("match", 5001L, "unknown", 5000L)) }
    @Test fun staleTimestampNeverAuthorizesRecovery() { assertFalse(BotEngine.staleRecoveryAuthorized("match", 1999L, "unknown", 5000L)) }
    @Test fun exactFreshnessBoundaryRetainsRecovery() { assertTrue(BotEngine.staleRecoveryAuthorized("match", 2000L, "unknown", 5000L)) }
}
