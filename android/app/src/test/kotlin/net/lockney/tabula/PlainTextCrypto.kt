package net.lockney.tabula

/** Identity "encryption" so the JVM tests never touch the Android Keystore. */
object PlainTextCrypto : CredentialCrypto {
    override fun encrypt(plainText: String): String = "enc:$plainText"

    override fun decrypt(blob: String): String = blob.removePrefix("enc:")
}
