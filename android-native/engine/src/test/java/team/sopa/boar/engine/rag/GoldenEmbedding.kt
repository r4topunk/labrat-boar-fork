package team.sopa.boar.engine.rag

/**
 * The golden exporter's stand-in embedding: content words hashed (FNV-1a over
 * code points) into 384 floats, as a Float32Array.
 */
object GoldenEmbedding {
    private val STOP = ("what when where which who whom why how is are was were be the a an of in on at to and or for from with does do did can could " +
        "should would tell me about it its this that there qual quais o os a as que é um uma de do da dos das em no na por para com como").split(" ").toSet()

    fun hashVec(text: String): FloatArray {
        val v = FloatArray(384)
        for (m in Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase())) {
            if (m.value in STOP) continue
            var h = 2166136261L.toInt()
            m.value.codePoints().forEach { cp -> h = (h xor cp) * 16777619 }
            val u = h.toLong() and 0xffffffffL
            v[(u % 384).toInt()] += 1f
            v[((u ushr 9) % 384).toInt()] -= 0.5f
        }
        return v
    }
}
