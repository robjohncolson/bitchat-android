package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 3 defense-in-depth (docs/dogecoin-spv-phase3-plan.md, FC7): MAINNET SPV broadcast is blocked at
 * four layers; this pins the DATA-SOURCE layer. The `require(network != MAINNET)` must fire before the
 * signed hex is normalized or the service is touched, so a mainnet broadcast can never reach the wire in
 * this phase. Robolectric only provides a Context to construct the (never-started) SPV service singleton.
 */
@RunWith(RobolectricTestRunner::class)
class DogecoinSpvBroadcastDataSourceTest {

    @Test
    fun `spv data source refuses mainnet broadcast before touching the service`() {
        val context = RuntimeEnvironment.getApplication()
        val repository = DogecoinWalletRepository(context)
        val service = DogecoinSpvService.getInstance(context, repository)
        val dataSource = DogecoinSpvDataSource(service)
        assertThrows(IllegalArgumentException::class.java) {
            // Hex is irrelevant — the mainnet guard throws before any normalize/service call.
            runBlocking { dataSource.broadcast("0100000000", DogecoinNetwork.MAINNET) }
        }
    }
}
