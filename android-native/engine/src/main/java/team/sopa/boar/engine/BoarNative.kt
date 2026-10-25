package team.sopa.boar.engine

/** Receives generated text as UTF-8 bytes; return false to stop generating. */
fun interface PieceCallback {
    fun onPiece(bytes: ByteArray): Boolean
}

/** The JNI surface of libboar_engine.so (src/main/cpp/boar_jni.cpp). Handles are native pointers. */
internal object BoarNative {
    init {
        System.loadLibrary("boar_engine")
    }

    external fun backendInit(nativeLibDir: String, cpuVariant: String?)
    external fun systemInfo(): String
    external fun load(path: String, paramsJson: String): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)
    external fun modelInfo(handle: Long): String
    external fun formatChat(handle: Long, messagesJson: String, enableThinking: Boolean): ByteArray
    external fun generate(handle: Long, prompt: ByteArray, samplingJson: String, callback: PieceCallback): ByteArray
    external fun embed(handle: Long, text: ByteArray, normalize: Boolean): FloatArray
    external fun bench(handle: Long, nPp: Int, nTg: Int, reps: Int): String
}
