package team.sopa.boar.engine.rag

/**
 * Which sources a search may read (Answer options → Sources): the built-in knowledge base's seed chunks (Wikipedia
 * essentials), the user's own collections, and the installed packs by id ([packs] null = every installed pack, so a
 * pack installed later is searched). [ownOnly]: only the user's files, the files-only answer mode.
 */
data class SourceFilter(val seed: Boolean = true, val own: Boolean = true, val packs: Set<String>? = null) {
    val ownOnly: Boolean get() = own && !seed && packs?.isEmpty() == true

    fun allowsPack(id: String): Boolean = packs == null || id in packs

    companion object {
        val ALL = SourceFilter()
        val OWN_ONLY = SourceFilter(seed = false, own = true, packs = emptySet())
    }
}
