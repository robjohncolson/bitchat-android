package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogepaidPaymentContextTest {
    private val address = DogecoinBase58.encodeChecked(
        DogecoinNetwork.TESTNET.p2pkhAddressHeader,
        ByteArray(20) { (it + 1).toByte() }
    )
    private val request = DogecoinPaymentRequest(
        network = DogecoinNetwork.TESTNET,
        address = address,
        amount = "1",
        uri = DogecoinProtocol.createPaymentUri(
            DogecoinNetwork.TESTNET,
            address,
            amount = "1"
        )
    )

    @Test
    fun `direct target must be a private conversation key`() {
        assertNull(DogepaidPaymentContext.forPrivateConversation(request, ""))
        assertNull(DogepaidPaymentContext.forPrivateConversation(request, "#public"))
        assertNull(DogepaidPaymentContext.forPrivateConversation(request, "geo:public"))
        assertNull(DogepaidPaymentContext.forPrivateConversation(request, "nostr_grp_deadbeef"))

        val context = DogepaidPaymentContext.forPrivateConversation(request, "nostr_0123456789abcdef")
        assertEquals(
            DogepaidReceiptDestination.PrivateConversation("nostr_0123456789abcdef"),
            context?.destination
        )
    }

    @Test
    fun `group target comes only from structural full pubkey`() {
        assertNull(DogepaidPaymentContext.forGroupRequester(request, null))
        assertNull(DogepaidPaymentContext.forGroupRequester(request, "display-name"))
        assertNull(DogepaidPaymentContext.forGroupRequester(request, "ab".repeat(31)))

        val uppercase = "AB".repeat(32)
        val context = DogepaidPaymentContext.forGroupRequester(request, uppercase)
        assertEquals(
            DogepaidReceiptDestination.NostrRequester("ab".repeat(32)),
            context?.destination
        )
        assertEquals("nostr_${"ab".repeat(8)}", dogepaidRequesterConversationKey(uppercase))
        assertTrue(context?.paymentUri?.startsWith("dogecoin:") == true)
    }

    @Test
    fun `delivery plan binds request claim and explicit direct target`() {
        val context = DogepaidPaymentContext.forPrivateConversation(request, "nostr_0123456789abcdef")!!
        val claim = claim()

        val plan = DogepaidReceiptDeliveryPlanner.plan(context, claim, "my-peer", null)!!

        assertEquals("nostr_0123456789abcdef", plan.conversationKey)
        assertEquals(null, plan.requesterNostrPubkeyHex)
        assertEquals(claim.txid, plan.receipt.txid)
        assertEquals(DogepaidReceipt.reqRef(request.uri), plan.receipt.requestRef)
    }

    @Test
    fun `delivery plan rejects self public group and mismatched claims`() {
        val direct = DogepaidPaymentContext.forPrivateConversation(request, "nostr_0123456789abcdef")!!
        assertNull(DogepaidReceiptDeliveryPlanner.plan(direct, claim(), "nostr_0123456789abcdef", null))

        val forgedPublic = DogepaidPaymentContext(
            request.uri,
            DogepaidReceiptDestination.PrivateConversation("#public")
        )
        assertNull(DogepaidReceiptDeliveryPlanner.plan(forgedPublic, claim(), "my-peer", null))

        assertNull(
            DogepaidReceiptDeliveryPlanner.plan(
                direct,
                claim(recipientAddress = DogecoinBase58.encodeChecked(
                    DogecoinNetwork.TESTNET.p2pkhAddressHeader,
                    ByteArray(20) { (it + 2).toByte() }
                )),
                "my-peer",
                null
            )
        )
        assertNull(
            DogepaidReceiptDeliveryPlanner.plan(
                direct,
                claim(txid = "AB".repeat(32)),
                "my-peer",
                null
            )
        )
    }

    @Test
    fun `group delivery plan is requester DM and rejects own structural identity`() {
        val requester = "ab".repeat(32)
        val context = DogepaidPaymentContext.forGroupRequester(request, requester)!!

        val plan = DogepaidReceiptDeliveryPlanner.plan(context, claim(), "my-peer", null)!!
        assertEquals("nostr_${requester.take(16)}", plan.conversationKey)
        assertEquals(requester, plan.requesterNostrPubkeyHex)
        assertNull(DogepaidReceiptDeliveryPlanner.plan(context, claim(), "my-peer", requester.uppercase()))
    }

    private fun claim(
        txid: String = "cd".repeat(32),
        recipientAddress: String = address
    ) = DogepaidBroadcastClaim(
        network = DogecoinNetwork.TESTNET,
        txid = txid,
        amountKoinu = 100_000_000L,
        recipientAddress = recipientAddress
    )
}
