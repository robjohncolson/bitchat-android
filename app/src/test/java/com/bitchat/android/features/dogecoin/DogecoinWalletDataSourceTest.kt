package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test

class DogecoinWalletDataSourceTest {

    /**
     * Guardrail: introducing the data-source abstraction must NOT turn the explorer into a broadcast
     * backend. The explorer adapter blocks broadcast (build+sign stays on-device; broadcast goes via a
     * node or the mesh). The console keeps its own explicit, mainnet-refused explorer-broadcast path.
     */
    @Test
    fun `explorer data source blocks broadcast`() {
        // Plain OkHttpClient keeps this a pure-JVM test; broadcast throws before any network use anyway.
        val explorer = DogecoinExplorerClient(httpClient = OkHttpClient())
        val dataSource = DogecoinExplorerDataSource(explorer)
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { dataSource.broadcast("0100000000", DogecoinNetwork.MAINNET) }
        }
    }
}
