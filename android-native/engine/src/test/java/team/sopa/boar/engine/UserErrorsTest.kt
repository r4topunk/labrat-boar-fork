package team.sopa.boar.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.models.AssetStore.DownloadException
import team.sopa.boar.engine.models.StorageBudget

class UserErrorsTest {
    @Test fun `no model gets its own text, anything else the generic one`() {
        assertEquals("native.chat.errorNoModel", UserErrors.chatKey(UserErrors.NO_MODEL))
        assertEquals("native.chat.errorGeneric", UserErrors.chatKey("java.lang.IllegalStateException: model unloaded"))
    }

    @Test fun `a Hugging Face search offline says so, anything else is generic`() {
        assertEquals("native.browse.errorOffline", UserErrors.browseKey(java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")))
        assertEquals("native.browse.errorOffline", UserErrors.browseKey(java.io.IOException("wrapped", java.net.ConnectException())))
        assertEquals("native.browse.errorGeneric", UserErrors.browseKey(IllegalStateException("HTTP 500")))
    }

    @Test fun `an answer that came back empty says so`() {
        assertEquals("native.chat.errorEmpty", UserErrors.chatKey(UserErrors.EMPTY_ANSWER))
    }

    @Test fun `download failures map by cause`() {
        assertEquals("native.download.errorStorage", UserErrors.downloadKey(DownloadException.Storage("needs 2 GB")))
        assertEquals("native.download.errorOffline", UserErrors.downloadKey(DownloadException.OfflineBuild()))
        val gated = team.sopa.boar.engine.models.CatalogAsset("g", team.sopa.boar.engine.models.AssetKind.LLM, "g", null, "models/g.gguf", 1, "", "", "", "", false)
        assertEquals("native.download.errorGated", UserErrors.downloadKey(DownloadException.Http(gated, 401)))
        assertEquals("native.download.errorGated", UserErrors.downloadKey(DownloadException.Http(gated, 403)))
        assertEquals("native.download.errorServer", UserErrors.downloadKey(DownloadException.Http(gated, 503)))
        assertEquals("native.download.errorNetwork", UserErrors.downloadKey(java.net.UnknownHostException("huggingface.co")))
        assertEquals("native.download.errorNetwork", UserErrors.downloadKey(java.net.SocketTimeoutException()))
        assertEquals("native.download.errorGeneric", UserErrors.downloadKey(IllegalStateException("boom")))
    }

    private val gb = 1_000_000_000L
    private fun refused(reason: StorageBudget.Reason, downloading: Long) = DownloadException.Storage(
        "x", StorageBudget.Check.Refused(reason, 0, "x", budgetBytes = 50 * gb, usedBytes = 20 * gb, downloadingBytes = downloading, neededBytes = 5 * gb, freeDiskBytes = 233 * gb),
    )

    @Test fun `BOAR's own limit never says the phone is full`() {
        assertEquals("native.download.errorBudgetDownloading", UserErrors.downloadKey(refused(StorageBudget.Reason.BUDGET, 12 * gb)))
        assertEquals("native.download.errorBudget", UserErrors.downloadKey(refused(StorageBudget.Reason.BUDGET, 0)))
        assertEquals("native.download.errorStorage", UserErrors.downloadKey(refused(StorageBudget.Reason.DISK, 0)))
    }

    @Test fun `storage texts get the real numbers`() {
        val args = UserErrors.downloadArgs(refused(StorageBudget.Reason.BUDGET, 12 * gb), java.util.Locale.ENGLISH).toMap()
        assertEquals("50 GB", args["budget"]); assertEquals("20 GB", args["used"]); assertEquals("12 GB", args["downloading"])
        assertEquals("5 GB", args["needed"]); assertEquals("233 GB", args["free"])
        assertEquals(0, UserErrors.downloadArgs(IllegalStateException("boom")).size)
    }
}
