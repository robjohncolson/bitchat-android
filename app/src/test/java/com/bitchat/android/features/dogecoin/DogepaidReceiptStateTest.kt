package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DogepaidReceiptStateTest {
    private val network = DogecoinNetwork.TESTNET
    private val walletAddress = addressFor(network, 1)
    private val txid = "23".repeat(32)
    private val receipt = DogepaidReceipt(
        network = network,
        txid = txid,
        amountKoinu = 500_000_000L,
        toAddress = walletAddress
    )

    @Test
    fun `receipt remains a claim without local observation`() {
        assertEquals(
            DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED),
            resolve(emptyList())
        )
    }

    @Test
    fun `same network txid wallet address and incoming observation corroborates`() {
        val state = resolve(
            listOf(
                observation(
                    amountKoinu = 475_000_000L,
                    confirmations = 3
                )
            )
        )

        assertEquals(
            DogepaidReceiptState.Corroborated(
                observedAmountKoinu = 475_000_000L,
                confirmations = 3
            ),
            state
        )
    }

    @Test
    fun `corroborated state uses observed amount and depth not receipt claim`() {
        val state = resolve(
            listOf(observation(amountKoinu = 125_000_000L, confirmations = 0))
        ) as DogepaidReceiptState.Corroborated

        assertEquals(125_000_000L, state.observedAmountKoinu)
        assertEquals(0, state.confirmations)
        assertTrue(state.observedAmountKoinu != receipt.amountKoinu)
    }

    @Test
    fun `cross-network wallet never corroborates or consumes observation`() {
        val state = DogepaidReceiptStateResolver.resolve(
            receipt = receipt,
            walletNetwork = DogecoinNetwork.MAINNET,
            walletAddress = addressFor(DogecoinNetwork.MAINNET, 1),
            observations = listOf(observation())
        )

        assertEquals(
            DogepaidReceiptState.Claimed(DogepaidClaimReason.CROSS_NETWORK),
            state
        )
    }

    @Test
    fun `receipt for a different wallet never corroborates`() {
        val state = DogepaidReceiptStateResolver.resolve(
            receipt = receipt,
            walletNetwork = network,
            walletAddress = addressFor(network, 2),
            observations = listOf(observation())
        )

        assertEquals(
            DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_FOR_THIS_WALLET),
            state
        )
    }

    @Test
    fun `wrong txid address network or direction stays claimed`() {
        val otherAddress = addressFor(network, 2)
        val nonMatches = listOf(
            observation(txid = "45".repeat(32)),
            observation(walletAddress = otherAddress),
            observation(network = DogecoinNetwork.MAINNET),
            observation(incoming = false)
        )

        nonMatches.forEach { candidate ->
            assertEquals(
                DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED),
                resolve(listOf(candidate))
            )
        }
    }

    @Test
    fun `invalid local amount or depth stays claimed fail closed`() {
        listOf(
            observation(amountKoinu = 0L),
            observation(amountKoinu = -1L),
            observation(confirmations = -1)
        ).forEach { candidate ->
            assertEquals(
                DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED),
                resolve(listOf(candidate))
            )
        }
    }

    @Test
    fun `conflicting local amounts stay claimed fail closed`() {
        val state = resolve(
            listOf(
                observation(amountKoinu = 100_000_000L, confirmations = 1),
                observation(amountKoinu = 200_000_000L, confirmations = 2)
            )
        )

        assertEquals(
            DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED),
            state
        )
    }

    @Test
    fun `duplicate consistent local rows use deepest observed confirmation`() {
        val state = resolve(
            listOf(
                observation(amountKoinu = 100_000_000L, confirmations = 1),
                observation(amountKoinu = 100_000_000L, confirmations = 4)
            )
        )

        assertEquals(
            DogepaidReceiptState.Corroborated(100_000_000L, 4),
            state
        )
    }

    private fun resolve(observations: List<DogepaidLocalObservation>): DogepaidReceiptState =
        DogepaidReceiptStateResolver.resolve(
            receipt = receipt,
            walletNetwork = network,
            walletAddress = walletAddress,
            observations = observations
        )

    private fun observation(
        network: DogecoinNetwork = this.network,
        txid: String = this.txid,
        walletAddress: String = this.walletAddress,
        incoming: Boolean = true,
        amountKoinu: Long = 500_000_000L,
        confirmations: Int = 1
    ) = DogepaidLocalObservation(
        network = network,
        txid = txid,
        walletAddress = walletAddress,
        incoming = incoming,
        amountKoinu = amountKoinu,
        confirmations = confirmations
    )

    private fun addressFor(network: DogecoinNetwork, seed: Int): String =
        DogecoinBase58.encodeChecked(network.p2pkhAddressHeader, ByteArray(20) { (it + seed).toByte() })
}
