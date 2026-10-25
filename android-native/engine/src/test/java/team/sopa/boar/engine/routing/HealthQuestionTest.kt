package team.sopa.boar.engine.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which questions count as health questions (QueryContext, r4to's list). */
class HealthQuestionTest {
    @Test fun firstAidAndInjuriesAreHealth() {
        listOf(
            "When should a burn see a doctor?",
            "How do I treat a minor kitchen burn?",
            "My hiking partner is shivering, confused and slurring words",
            "I was stung by a box jellyfish while swimming. Should I rinse it?",
            "My child spilled boiling water on their arm. What do I do?",
        ).forEach { assertTrue(it, QueryContext.isHealthQuestion(it)) }
    }

    @Test fun researchQuestionsAreNot() {
        listOf(
            "What are the main sights to see in Lisbon?",
            "What is the difference between optimistic and zk-rollups?",
            "What is a seed phrase in a crypto wallet?",
            "Which countries are bigger than India by area?",
        ).forEach { assertFalse(it, QueryContext.isHealthQuestion(it)) }
    }
}
