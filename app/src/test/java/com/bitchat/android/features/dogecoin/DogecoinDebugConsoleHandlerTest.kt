package com.bitchat.android.features.dogecoin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DogecoinDebugConsoleHandlerTest {
    private lateinit var context: Context
    private lateinit var repository: DogecoinWalletRepository
    private lateinit var scope: CoroutineScope
    private lateinit var handler: DogecoinDebugConsoleHandler
    private var spvAccesses = 0
    private var peerBroadcasts = 0
    private var reannouncements = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearDogecoinPrefs()
        repository = DogecoinWalletRepository(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        handler = DogecoinDebugConsoleHandler(
            walletRepository = repository,
            coroutineScope = scope,
            spv = {
                spvAccesses += 1
                error("SPV must not be accessed by a refused command")
            },
            requestPeerBroadcast = { peerBroadcasts += 1 },
            peerBroadcastState = MutableStateFlow(PeerBroadcastUiState.Idle),
            reannounceIdentity = { reannouncements += 1 },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        clearDogecoinPrefs()
    }

    @Test
    fun `mainnet hard refusals remain exact and side effect free`() {
        repository.saveSelectedNetwork(DogecoinNetwork.MAINNET)

        val cases = listOf(
            RefusalCase(
                "doge-import-wif",
                listOf("secret-wif"),
                "refused: mainnet key import is console-blocked",
            ),
            RefusalCase(
                "doge-reset",
                emptyList(),
                "refused: mainnet wallet reset is console-blocked " +
                    "(use doge-reset-mainnet <currentAddress> to confirm)",
            ),
            RefusalCase(
                "doge-spv-broadcast",
                listOf("destination", "1"),
                "refused: mainnet broadcast is console-blocked",
            ),
            RefusalCase(
                "doge-self-broadcast",
                listOf("destination", "1"),
                "refused: mainnet broadcast is console-blocked",
            ),
            RefusalCase(
                "doge-peer-broadcast",
                listOf("destination", "1"),
                "refused: mainnet broadcast is console-blocked",
            ),
            RefusalCase(
                "doge-spv-peer-broadcast",
                listOf("destination", "1"),
                "refused: mainnet broadcast is console-blocked",
            ),
            RefusalCase(
                "doge-explorer-broadcast",
                listOf("raw-transaction"),
                "refused: mainnet broadcast is console-blocked",
            ),
            RefusalCase(
                "doge-explorer-send",
                listOf("destination", "1"),
                "refused: mainnet broadcast is console-blocked",
            ),
        )

        cases.forEach { case ->
            assertEquals(case.command, case.expected, handler.handleOrNull(case.command, case.args))
        }
        assertNoDelegatedSideEffects()
    }

    @Test
    fun `special mainnet commands keep their non-mainnet refusals before usage checks`() {
        repository.saveSelectedNetwork(DogecoinNetwork.TESTNET)

        assertEquals(
            "refused: doge-spv-mainnet-send is mainnet-only " +
                "(use doge-spv-broadcast on testnet)",
            handler.handleOrNull("doge-spv-mainnet-send", emptyList()),
        )
        assertEquals(
            "refused: not on mainnet (use doge-reset for testnet)",
            handler.handleOrNull("doge-reset-mainnet", emptyList()),
        )
        assertNoDelegatedSideEffects()
    }

    @Test
    fun `non-Dogecoin commands fall through to the ViewModel host`() {
        assertNull(handler.handleOrNull("peers", emptyList()))
        assertNull(handler.handleOrNull("doge-not-a-command", emptyList()))
        assertNoDelegatedSideEffects()
    }

    private fun assertNoDelegatedSideEffects() {
        assertEquals(0, spvAccesses)
        assertEquals(0, peerBroadcasts)
        assertEquals(0, reannouncements)
    }

    private fun clearDogecoinPrefs() {
        context.getSharedPreferences("dogecoin_wallet", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dogecoin_testnet_wallet", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private data class RefusalCase(
        val command: String,
        val args: List<String>,
        val expected: String,
    )
}
