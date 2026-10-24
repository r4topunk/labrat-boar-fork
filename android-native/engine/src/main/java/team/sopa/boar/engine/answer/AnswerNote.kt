package team.sopa.boar.engine.answer

/**
 * The one fixed line under every answer the model wrote (r4to, Oct 24 18:0x): it replaces the per-answer warning trigger
 * (the port of ENGINE-RC's released compact declines). The chat draws it as small secondary text, never inside the
 * answer's text, so history, Copy and the model's next prompt never carry it. Not on the fixed health text ([HealthGate]),
 * My files' not-found line, or small talk.
 */
object AnswerNote {
    const val LINE = "May contain errors; check the source."
    /** isCompactModel's size bar (answer.ts): a model this size or smaller gets CT-1 and attribution; Qwen3 1.7B Q4_K_M is 1.11e9 B. */
    const val COMPACT_MODEL_BYTES = 1_600_000_000L
}
