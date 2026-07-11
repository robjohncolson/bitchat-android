package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DogepaidLocalizationTest {
    @Test
    fun `receipt and network strings have English and Japanese parity`() {
        val required = setOf(
            "dogecoin_network_mainnet",
            "dogecoin_network_testnet",
            "dogecoin_network_regtest",
            "dogepaid_receipt_title",
            "dogepaid_receipt_duplicate_title",
            "dogepaid_receipt_reported_amount",
            "dogepaid_receipt_txid",
            "dogepaid_receipt_claimed",
            "dogepaid_receipt_corroborated",
            "dogepaid_receipt_duplicate_body",
            "dogepaid_receipt_outgoing_claimed",
            "dogepaid_receipt_tap_status",
            "dogepaid_receipt_status_title",
            "dogepaid_receipt_checking",
            "dogepaid_receipt_confirmation_description",
            "dogepaid_receipt_close",
            "dogepaid_receipt_cross_network",
            "dogepaid_receipt_retry",
            "dogepaid_receipt_share_queued",
            "dogepaid_receipt_rpc_not_over_tor"
        )

        val english = stringKeys(resourceFile("values/strings.xml"))
        val japanese = stringKeys(resourceFile("values-ja/strings.xml"))
        assertTrue("Missing English keys: ${required - english}", english.containsAll(required))
        assertTrue("Missing Japanese keys: ${required - japanese}", japanese.containsAll(required))
    }

    private fun resourceFile(relative: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(workingDir, "src/main/res/$relative"),
            File(workingDir, "app/src/main/res/$relative")
        ).firstOrNull(File::isFile) ?: error("Could not locate $relative from $workingDir")
    }

    private fun stringKeys(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
}
