// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.data

data class ConnectionProfile(
    val name: String,
    val serverAddress: String,
    val serverPort: Int,
    val tlsServerName: String,
    val authKey: String,
    val mtu: Int,
    val controlAddress: String,
    val controlPort: Int,
    val iperfHost: String,
    val iperfPort: Int,
    val insecure: Boolean = false,
    val serverControlAllowed: Boolean = false,
)
