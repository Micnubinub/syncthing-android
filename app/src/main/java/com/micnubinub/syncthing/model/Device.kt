package com.micnubinub.syncthing.model

import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.common.io.BaseEncoding
import com.micnubinub.syncthing.util.Luhn
import java.util.Locale

@Immutable
data class Device(
    var deviceID: String? = null,
    var name: String = "",
    var addresses: MutableList<String> = mutableListOf(),
    var allowedNetworks: MutableList<String> = mutableListOf(),
    var compression: String = "metadata",
    var certName: String? = null,
    var introducedBy: String = "",
    var introducer: Boolean = false,
    var paused: Boolean = false,
    var ignoredFolders: MutableList<IgnoredFolder>? = null,
    var autoAcceptFolders: Boolean = false,
    var maxRecvKbps: Int = 0,
    var maxSendKbps: Int = 0,
    var untrusted: Boolean = false,
    var numConnections: Int = 0
) {
    /**
     * Relevant fields for Folder.List<Device> "shared-with-device" model,
     * handled by [and ConfigXml#updateFolder][ConfigRouter.updateFolder]
     * deviceID
     * introducedBy
     * Example Tag
     * <folder ...>
     * <device id="[DEVICE_SHARING_THAT_FOLDER_WITH_US]" introducedBy="[INTRODUCER_DEVICE_THAT_TOLD_US_ABOUT_THE_FOLDER_OR_EMPTY_STRING]"></device>
    </folder> * 
    </Device> */
    val displayName: String?
        /**
         * Returns the device name, or the first characters of the ID if the name is empty.
         */
        get() = if (name.isNullOrEmpty())
            (if (deviceID.isNullOrEmpty()) "" else deviceID?.take(7))
        else
            name

    /**
     * Returns if a syncthing device ID is correctly formatted.
     */
    fun checkDeviceID(): Boolean {
        /**
         * See https://github.com/syncthing/syncthing/blob/master/lib/protocol/deviceid.go
         * how syncthing validates device IDs.
         * Old dirty way to check was: return deviceID.matches("^([A-Z0-9]{7}-){7}[A-Z0-9]{7}$");
         */
        var deviceID = deviceID?.replace("=".toRegex(), "")
            ?.uppercase(Locale.ROOT)
            ?.replace("1".toRegex(), "I")
            ?.replace("0".toRegex(), "O")
            ?.replace("8".toRegex(), "B")
            ?.replace("-".toRegex(), "")
            ?.replace(" ".toRegex(), "")

        // Check length.
        when (deviceID?.length) {
            0 -> return false
            56 -> {
                // unluhnify(deviceID)
                val bytesIn = deviceID.toByteArray()
                val res = ByteArray(52)
                var i = 0
                while (i < 4) {
                    val p = bytesIn.copyOfRange(i * (13 + 1), (i + 1) * (13 + 1) - 1)
                    System.arraycopy(p, 0, res, i * 13, 13)

                    // Generate check digit.
                    val luhn = Luhn()
                    val checkRune = luhn.generate(p)
                        ?: // Log.w(TAG, "checkDeviceID: deviceID=(" + deviceID + "): invalid character");
                        return false
                    // Log.v(TAG, "checkDeviceID: luhn.generate(" + new String(p) + ") returned (" + checkRune + ")");
                    if (deviceID.substring((i + 1) * 14 - 1, (i + 1) * 14 - 1 + 1) != checkRune) {
                        // Log.w(TAG, "checkDeviceID: deviceID=(" + deviceID + "): check digit incorrect");
                        return false
                    }
                    i++
                }
                deviceID = String(res)
                try {
                    BaseEncoding.base32().decode("$deviceID====")
                    return true
                } catch (e: IllegalArgumentException) {
                    // Log.w(TAG, "checkDeviceID: deviceID=(" + deviceID + "): invalid character, base32 decode failed");
                    return false
                }

            }

            52 -> {
                try {
                    BaseEncoding.base32().decode("$deviceID====")
                    return true
                } catch (e: IllegalArgumentException) {
                    return false
                }
            }

            else -> return false
        }
    }

    /**
     * Returns if device.addresses elements are correctly formatted.
     * See https://docs.syncthing.net/users/config.html#device-element for what is correct.
     * It can be improved in the future because it doesn't catch all mistakes a user can do.
     * It catches the most common mistakes.
     */
    fun checkDeviceAddresses(): Boolean {
        if (!testCheckDeviceAddress()) {
            Log.e(TAG, "checkDeviceAddresses: testCheckDeviceAddress unit test failed")
            return false
        }

        for (address in this.addresses) {
            // Log.v(TAG, "address=(" + address + ")");
            if (!checkDeviceAddress(address)) {
                return false
            }
        }
        return true
    }

    private fun checkDeviceAddress(address: String): Boolean {
        if (address == "dynamic") {
            return true
        }

        if (!address.matches("^tcp([46])?://.*$".toRegex()) && !address.matches("^relay://.*$".toRegex()) && !address.matches(
                "^quic([46])?://.*$".toRegex()
            )
        ) {
            // Log.v(TAG, "Invalid protocol.");
            return false
        }

        // Separate protocol from address and port.
        val addressSplit = address.split("://".toRegex()).dropLastWhile { it.isEmpty() }
        if (addressSplit.size == 1) {
            // There's only the protocol given, nothing more.
            // Log.v(TAG, "There's only the protocol given, nothing more.");
            return false
        } else if (addressSplit.size == 2) {
            // Log.v(TAG, "strProtocol=" + addressSplit[0]);
            if (addressSplit[0].matches("^tcp.*$".toRegex())) {
                return checkDeviceAddressTcp(addressSplit[1])
            } else if (addressSplit[0].matches("^relay.*$".toRegex())) {
                return checkDeviceAddressRelay(addressSplit[1])
            } else if (addressSplit[0].matches("^quic.*$".toRegex())) {
                return checkDeviceAddressTcp(addressSplit[1])
            }
        }

        // Protocol is given more than one time. Will match "tcp://tcp://"
        return false
    }

    private fun checkDeviceAddressTcp(address: String): Boolean {
        // Check if the address ends with ":" or "]:"
        if (address.endsWith(":") ||
            address.endsWith("]:")
        ) {
            // The address ends with ":". Will match "tcp://myserver:"
            // Log.v(TAG, "address ends with \":\" or \"]:\". Will match \"tcp://myserver:\".");
            return false
        }

        // Check if there's a "hostname:port" number given in the part after "://".
        val hostnamePortSplit = address.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        if (hostnamePortSplit.size > 1) {
            // Check if the hostname or IP address given before the port is empty.
            if (hostnamePortSplit[0].isNullOrEmpty()) {
                // Empty hostname or IP address before the port. Will match "tcp://:4000"
                // Log.v(TAG, "Empty hostname or IP address before the port.");
                return false
            }

            // Check if there's a port number given in the last part.
            val potentialPort = hostnamePortSplit[hostnamePortSplit.size - 1].split("/".toRegex())
                .dropLastWhile { it.isEmpty() }[0]
            if (!potentialPort.endsWith("]")) {
                // It's not the end of an IPv6 address and likely a port number.
                // Log.v(TAG, "... potentialPort=(" + potentialPort + ")");
                val port = try {
                    potentialPort.toInt()
                } catch (e: Exception) {
                    0
                }
                if (port < 1 || port > 65535) {
                    // Invalid port number.
                    // Log.v(TAG, "Invalid port number.");
                    return false
                }
            }
        }

        return true
    }

    private fun checkDeviceAddressRelay(address: String): Boolean {
        // Check if the address ends with ":" or "]:"
        if (address.endsWith(":") || address.endsWith("]:")) {
            // The address ends with ":". Will match "relay://myserver:"
            // Log.v(TAG, "address ends with \":\" or \"]:\". Will match \"relay://myserver:\".");
            return false
        }

        // Check if there's a "hostname:port" number given in the part after "://".
        val hostnamePortSplit = address.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        if (hostnamePortSplit.size > 1) {
            // Check if the hostname or IP address given before the port is empty.
            if (hostnamePortSplit[0].isNullOrEmpty()) {
                // Empty hostname or IP address before the port. Will match "relay://:4000"
                // Log.v(TAG, "Empty hostname or IP address before the port.");
                return false
            }

            // Check if there's a port number given in the last part.
            val potentialPort =
                hostnamePortSplit[1].split("/".toRegex()).dropLastWhile { it.isEmpty() }[0]
            if (!potentialPort.endsWith("]")) {
                // It's not the end of an IPv6 address and likely a port number.
                // Log.v(TAG, "... potentialPort=(" + potentialPort + ")");
                var port = 0
                try {
                    port = potentialPort.toInt()
                } catch (e: Exception) {
                }
                if (port < 1 || port > 65535) {
                    // Invalid port number.
                    // Log.v(TAG, "Invalid port number.");
                    return false
                }
            }
        }

        return true
    }

    private fun testCheckDeviceAddress(): Boolean {
        var failSuccess = true

        // Positive Syntax
        failSuccess = failSuccess && checkDeviceAddress("tcp://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp4://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp6://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp4://127.0.0.1")
        failSuccess = failSuccess && checkDeviceAddress("tcp://[2001:db8::23:42]")
        failSuccess = failSuccess && checkDeviceAddress("tcp://[2001:db8::23:42]:12345")
        failSuccess = failSuccess && checkDeviceAddress("tcp://myserver")
        failSuccess = failSuccess && checkDeviceAddress("tcp://myserver:12345")
        failSuccess =
            failSuccess && checkDeviceAddress("relay://stlocal:22067/?id=ID-REDACTED&pingInterval=30s&networkTimeout=2m0s&sessionLimitBps=0&globalLimitBps=0&statusAddr=:22070&providedBy=REDACTED")
        failSuccess = failSuccess && checkDeviceAddress("relay://stlocal:22067")
        failSuccess = failSuccess && checkDeviceAddress("quic://127.0.0.1")
        failSuccess = failSuccess && checkDeviceAddress("quic://127.0.0.1:24000")
        failSuccess = failSuccess && checkDeviceAddress("quic4://127.0.0.1:24000")
        failSuccess = failSuccess && checkDeviceAddress("quic6://127.0.0.1:24000")

        // Negative Syntax
        failSuccess = failSuccess && !checkDeviceAddress("tcp://myserver:")
        failSuccess = failSuccess && !checkDeviceAddress("tcp8://127.0.0.1")
        failSuccess = failSuccess && !checkDeviceAddress("udp4://127.0.0.1")
        return failSuccess
    }

    companion object {
        private const val TAG = "Device"
    }
}
