package ilenreste.unpeu.recettesback.configuration;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

class RsaKeyConfigTest {

    private final RsaKeyConfig rsaKeyConfig = new RsaKeyConfig();

    @Test
    void keyPair_generatesA2048BitRsaKeyPair() {
        KeyPair keyPair = rsaKeyConfig.keyPair();

        assertThat(keyPair.getPublic().getAlgorithm()).isEqualTo("RSA");
        assertThat(keyPair.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(((RSAPublicKey) keyPair.getPublic()).getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void rsaPublicKey_returnsThePublicKeyFromTheGivenPair() {
        KeyPair keyPair = rsaKeyConfig.keyPair();

        RSAPublicKey publicKey = rsaKeyConfig.rsaPublicKey(keyPair);

        assertThat(publicKey).isEqualTo(keyPair.getPublic());
    }

    @Test
    void rsaPrivateKey_returnsThePrivateKeyFromTheGivenPair() {
        KeyPair keyPair = rsaKeyConfig.keyPair();

        RSAPrivateKey privateKey = rsaKeyConfig.rsaPrivateKey(keyPair);

        assertThat(privateKey).isEqualTo(keyPair.getPrivate());
    }
}
