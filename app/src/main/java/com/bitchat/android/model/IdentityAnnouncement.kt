package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class DogecoinIdentityAddress(
    val networkId: String,
    val address: String
) : Parcelable

@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray,  // Ed25519 public key for signing
    val dogecoinAddresses: List<DogecoinIdentityAddress> = emptyList(),
    val helperNetworks: List<String> = emptyList()
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u),  // NEW: Ed25519 signing public key
        DOGECOIN_ADDRESS(0x05u),
        NODE_HELPER(0x06u);         // NEW (3b): a network id this peer will broadcast transactions for
        
        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)

        val dogecoinAddressValues = mutableListOf<ByteArray>()
        val seenDogecoinNetworks = mutableSetOf<String>()
        dogecoinAddresses.forEach { dogecoinAddress ->
            if (dogecoinAddressValues.size >= MAX_DOGECOIN_ADDRESS_TLVS) return@forEach

            val networkId = dogecoinAddress.networkId.trim().lowercase()
            if (!seenDogecoinNetworks.add(networkId)) return@forEach

            val value = encodeDogecoinAddressValue(networkId, dogecoinAddress.address.trim()) ?: return null
            dogecoinAddressValues.add(value)
        }

        val helperNetworkValues = mutableListOf<ByteArray>()
        val seenHelperNetworks = mutableSetOf<String>()
        helperNetworks.forEach { network ->
            if (helperNetworkValues.size >= MAX_DOGECOIN_ADDRESS_TLVS) return@forEach
            val networkId = network.trim().lowercase()
            val networkData = networkId.toByteArray(Charsets.UTF_8)
            if (networkData.isEmpty() || networkData.size > MAX_DOGECOIN_NETWORK_ID_BYTES) return@forEach
            if (!seenHelperNetworks.add(networkId)) return@forEach
            helperNetworkValues.add(networkData)
        }

        // Check size limits
        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())
        
        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        dogecoinAddressValues.forEach { value ->
            result.add(TLVType.DOGECOIN_ADDRESS.value.toByte())
            result.add(value.size.toByte())
            result.addAll(value.toList())
        }

        helperNetworkValues.forEach { value ->
            result.add(TLVType.NODE_HELPER.value.toByte())
            result.add(value.size.toByte())
            result.addAll(value.toList())
        }

        return result.toByteArray()
    }

    private fun encodeDogecoinAddressValue(networkId: String, address: String): ByteArray? {
        val networkData = networkId.toByteArray(Charsets.UTF_8)
        val addressData = address.toByteArray(Charsets.UTF_8)
        if (networkData.isEmpty() || networkData.size > MAX_DOGECOIN_NETWORK_ID_BYTES) return null
        if (addressData.isEmpty() || addressData.size > MAX_DOGECOIN_ADDRESS_BYTES) return null

        val valueSize = 1 + networkData.size + addressData.size
        if (valueSize > 255) return null

        return byteArrayOf(networkData.size.toByte()) + networkData + addressData
    }
    
    companion object {
        private const val MAX_DOGECOIN_ADDRESS_TLVS = 3
        private const val MAX_DOGECOIN_NETWORK_ID_BYTES = 16
        private const val MAX_DOGECOIN_ADDRESS_BYTES = 64

        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null
            val dogecoinAddresses = linkedMapOf<String, String>()
            val helperNetworks = linkedSetOf<String>()
            
            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1
                
                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > dataCopy.size) return null
                
                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length
                
                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    TLVType.DOGECOIN_ADDRESS -> {
                        decodeDogecoinAddressValue(value)?.let { dogecoinAddress ->
                            if (
                                dogecoinAddresses.size < MAX_DOGECOIN_ADDRESS_TLVS &&
                                !dogecoinAddresses.containsKey(dogecoinAddress.networkId)
                            ) {
                                dogecoinAddresses[dogecoinAddress.networkId] = dogecoinAddress.address
                            }
                        }
                    }
                    TLVType.NODE_HELPER -> {
                        if (helperNetworks.size < MAX_DOGECOIN_ADDRESS_TLVS) {
                            val networkId = String(value, Charsets.UTF_8).trim().lowercase()
                            if (networkId.isNotBlank()) helperNetworks.add(networkId)
                        }
                    }
                    null -> {
                        // Unknown TLV; skip (tolerant decoder for forward compatibility)
                        continue
                    }
                }
            }
            
            // All three fields are required
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(
                    nickname = nickname,
                    noisePublicKey = noisePublicKey,
                    signingPublicKey = signingPublicKey,
                    dogecoinAddresses = dogecoinAddresses.map { (networkId, address) ->
                        DogecoinIdentityAddress(networkId, address)
                    },
                    helperNetworks = helperNetworks.toList()
                )
            } else {
                null
            }
        }

        private fun decodeDogecoinAddressValue(value: ByteArray): DogecoinIdentityAddress? {
            if (value.isEmpty()) return null

            val networkLength = value[0].toUByte().toInt()
            if (networkLength <= 0 || networkLength > MAX_DOGECOIN_NETWORK_ID_BYTES) return null
            if (1 + networkLength >= value.size) return null

            val networkId = String(value, 1, networkLength, Charsets.UTF_8).trim().lowercase()
            val address = String(
                value,
                1 + networkLength,
                value.size - 1 - networkLength,
                Charsets.UTF_8
            ).trim()

            if (networkId.isBlank()) return null
            if (address.isBlank()) return null
            if (address.toByteArray(Charsets.UTF_8).size > MAX_DOGECOIN_ADDRESS_BYTES) return null

            return DogecoinIdentityAddress(networkId, address)
        }
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (dogecoinAddresses != other.dogecoinAddresses) return false
        if (helperNetworks != other.helperNetworks) return false

        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + dogecoinAddresses.hashCode()
        result = 31 * result + helperNetworks.hashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., dogecoinAddresses=${dogecoinAddresses.map { it.networkId }}, helperNetworks=$helperNetworks)"
    }
}
