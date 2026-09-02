package dev.willsrv.xlogin;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

final class CryptoUtil {

    private static final int PBKDF2_ITERATIONS = 150_000;
    private static final int KEY_LENGTH_BITS = 256;

    private CryptoUtil() {
    }

    record HashedSecret(String saltB64, String hashB64) {
    }

    static HashedSecret pbkdf2(char[] password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return new HashedSecret(Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
    }

    static boolean verifyPbkdf2(char[] password, String saltB64, String expectedHashB64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltB64);
            byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
            return MessageDigest.isEqual(hash, Base64.getDecoder().decode(expectedHashB64));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo derivar la contrasena", e);
        }
    }

    static String hmacSha256(byte[] secretKey, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("No se pudo cifrar la IP", e);
        }
    }
}
