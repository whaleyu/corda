package net.corda.testing.internal

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.sign
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.createDevNodeCa
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.testing.DEV_INTERMEDIATE_CA
import net.corda.testing.DEV_ROOT_CA
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.X509Certificate

class TestNodeInfoBuilder(private val intermediateAndRoot: Pair<CertificateAndKeyPair, X509Certificate> = DEV_INTERMEDIATE_CA to DEV_ROOT_CA.certificate) {
    private val identitiesAndPrivateKeys = ArrayList<Pair<PartyAndCertificate, PrivateKey>>()

    fun addIdentity(name: CordaX500Name, nodeKeyPair: KeyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)): Pair<PartyAndCertificate, PrivateKey> {
        val nodeCertificateAndKeyPair = createDevNodeCa(intermediateAndRoot.first, name, nodeKeyPair)
        val identityKeyPair = Crypto.generateKeyPair()
        val identityCert = X509Utilities.createCertificate(
                                CertificateType.LEGAL_IDENTITY,
                                nodeCertificateAndKeyPair.certificate,
                                nodeCertificateAndKeyPair.keyPair,
                                nodeCertificateAndKeyPair.certificate.subjectX500Principal,
                                identityKeyPair.public)
                val certPath = X509CertificateFactory()
                        .generateCertPath(identityCert,
                                nodeCertificateAndKeyPair.certificate,
                                intermediateAndRoot.first.certificate,
                                intermediateAndRoot.second)
                return Pair(PartyAndCertificate(certPath), identityKeyPair.private).also {
            identitiesAndPrivateKeys += it
        }
    }

    fun build(serial: Long = 1, platformVersion: Int = 1): NodeInfo {
        return NodeInfo(
                listOf(NetworkHostAndPort("my.${identitiesAndPrivateKeys[0].first.party.name.organisation}.com", 1234)),
                identitiesAndPrivateKeys.map { it.first },
                platformVersion,
                serial
        )
    }

    fun buildWithSigned(serial: Long = 1, platformVersion: Int = 1): Pair<NodeInfo, SignedNodeInfo> {
        val nodeInfo = build(serial, platformVersion)
        val privateKeys = identitiesAndPrivateKeys.map { it.second }
        return Pair(nodeInfo, nodeInfo.signWith(privateKeys))
    }

    fun reset() {
        identitiesAndPrivateKeys.clear()
    }
}

fun createNodeInfoAndSigned(vararg names: CordaX500Name, serial: Long = 1, platformVersion: Int = 1): Pair<NodeInfo, SignedNodeInfo> {
    val nodeInfoBuilder = TestNodeInfoBuilder()
    names.forEach { nodeInfoBuilder.addIdentity(it) }
    return nodeInfoBuilder.buildWithSigned(serial, platformVersion)
}

fun NodeInfo.signWith(keys: List<PrivateKey>): SignedNodeInfo {
    val serialized = serialize()
    val signatures = keys.map { it.sign(serialized.bytes) }
    return SignedNodeInfo(serialized, signatures)
}
