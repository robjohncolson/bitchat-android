package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.noise.NoiseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandProcessorTest() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var chatState: ChatState
    private lateinit var commandProcessor: CommandProcessor

    private lateinit var messageManager: MessageManager
    private lateinit var channelManager: ChannelManager

    private val meshService: MeshService = FakeMeshService()

    @Before
    fun setup() {
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        chatState = ChatState(scope = testScope)
        messageManager = MessageManager(state = chatState)
        channelManager = ChannelManager(
            state = chatState,
            messageManager = messageManager,
            dataManager = DataManager(context = context),
            coroutineScope = testScope
        )
        commandProcessor = CommandProcessor(
            state = chatState,
            messageManager = messageManager,
            channelManager = channelManager,
            privateChatManager = PrivateChatManager(
                state = chatState,
                messageManager = messageManager,
                dataManager = DataManager(context = context),
                noiseSessionDelegate = mock<NoiseSessionDelegate>()
            )
        )
    }

    @Test
    fun `when using lower case join command, command returns true`() {
        val channel = "channel-1"

        val result = commandProcessor.processCommand(
            command = "/j $channel",
            meshService = meshService,
            myPeerID = "peer-id",
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertEquals(result, true)
    }

    @Test
    fun `when using upper case join command, command returns true`() {
        val channel = "channel-1"

        val result = commandProcessor.processCommand(
            command = "/JOIN $channel",
            meshService = meshService,
            myPeerID = "peer-id",
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertEquals(result, true)
    }

    @Test
    fun `when unknown command lower case is given, command returns true but does not process special handling`() {
        val channel = "channel-1"

        val result = commandProcessor.processCommand(
            command = "/wtfjoin $channel",
            meshService = meshService,
            myPeerID = "peer-id",
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertEquals(result, true)
    }

    @Test
    fun `channel command suggestions only include implemented command handlers`() {
        channelManager.joinChannel("#ops", myPeerID = "peer-id")

        commandProcessor.updateCommandSuggestions("/")

        val commands = chatState.commandSuggestions.value.map { it.command }
        assertTrue(commands.contains("/pass"))
        assertFalse(commands.contains("/save"))
        assertFalse(commands.contains("/transfer"))
    }

    @Test
    fun `joining channel with password derives local channel key`() {
        val joined = channelManager.joinChannel("#ops", password = "secret", myPeerID = "peer-id")

        assertTrue(joined)
        assertTrue(channelManager.isChannelPasswordProtected("#ops"))
        assertTrue(channelManager.hasChannelKey("#ops"))
    }

    @Test
    fun `encrypted channel send creates decryptable encrypted payload`() {
        channelManager.joinChannel("#ops", myPeerID = "peer-id")
        channelManager.setChannelPassword("#ops", "secret")

        var encryptedPayload: ByteArray? = null
        var usedFallback = false

        channelManager.sendEncryptedChannelMessage(
            content = "hello channel",
            mentions = emptyList(),
            channel = "#ops",
            senderNickname = "me",
            myPeerID = "peer-id",
            onEncryptedPayload = { encryptedPayload = it },
            onFallback = { usedFallback = true }
        )

        assertFalse(usedFallback)
        assertNotNull(encryptedPayload)

        val encrypted = encryptedPayload!!
        assertNotEquals("hello channel", String(encrypted, Charsets.UTF_8))
        assertEquals("hello channel", channelManager.decryptChannelMessage(encrypted, "#ops"))
    }

    @Test
    fun `wrong channel password is rejected when commitment was persisted`() {
        channelManager.joinChannel("#ops", myPeerID = "peer-id")
        channelManager.setChannelPassword("#ops", "secret")

        val reloadedState = ChatState(scope = testScope)
        val reloadedMessageManager = MessageManager(state = reloadedState)
        val reloadedChannelManager = ChannelManager(
            state = reloadedState,
            messageManager = reloadedMessageManager,
            dataManager = DataManager(context = context),
            coroutineScope = testScope
        )

        val (joinedChannels, protectedChannels) = reloadedChannelManager.loadChannelData()
        reloadedState.setJoinedChannels(joinedChannels)
        reloadedState.setPasswordProtectedChannels(protectedChannels)

        val joinedWithWrongPassword = reloadedChannelManager.joinChannel(
            "#ops",
            password = "wrong",
            myPeerID = "peer-id"
        )

        assertFalse(joinedWithWrongPassword)
        assertFalse(reloadedChannelManager.hasChannelKey("#ops"))
    }

    private class FakeMeshService : MeshService {
        override val myPeerID: String = "peer-id"
        override var delegate: MeshDelegate? = null
        private val nicknames = mutableMapOf("peer-id" to "me")

        override fun startServices() = Unit
        override fun stopServices() = Unit
        override fun sendMessage(content: String, mentions: List<String>, channel: String?) = Unit
        override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) = Unit
        override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) = Unit
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
        override fun sendPaymentBroadcastRequest(peerID: String, payload: ByteArray) = Unit
        override fun sendPaymentBroadcastResult(peerID: String, payload: ByteArray) = Unit
        override fun sendFileBroadcast(file: BitchatFilePacket) = Unit
        override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) = Unit
        override fun cancelFileTransfer(transferId: String): Boolean = false
        override fun sendBroadcastAnnounce() = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun getPeerNicknames(): Map<String, String> = nicknames
        override fun getPeerRSSI(): Map<String, Int> = emptyMap()
        override fun getActivePeerCount(): Int = 0
        override fun hasEstablishedSession(peerID: String): Boolean = false
        override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState = NoiseSession.NoiseSessionState.Uninitialized
        override fun initiateNoiseHandshake(peerID: String) = Unit
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun updatePeerInfo(
            peerID: String,
            nickname: String,
            noisePublicKey: ByteArray,
            signingPublicKey: ByteArray,
            isVerified: Boolean
        ): Boolean = false
        override fun getIdentityFingerprint(): String = "fingerprint"
        override fun getStaticNoisePublicKey(): ByteArray? = null
        override fun shouldShowEncryptionIcon(peerID: String): Boolean = false
        override fun getEncryptedPeers(): List<String> = emptyList()
        override fun getDeviceAddressForPeer(peerID: String): String? = null
        override fun getDeviceAddressToPeerMapping(): Map<String, String> = emptyMap()
        override fun printDeviceAddressesForPeers(): String = ""
        override fun getDebugStatus(): String = ""
        override fun clearAllInternalData() = Unit
        override fun clearAllEncryptionData() = Unit
    }
}
