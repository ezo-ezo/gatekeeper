package dev.gatekeeper.ticket;

import java.io.ByteArrayOutputStream;

/**
 * RFC 4648 base32 with padding stripped, the conventional way a TOTP secret
 * is written down (the same alphabet apps like Google Authenticator use for
 * "enter this key manually" setup screens).
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                out.append(ALPHABET.charAt((buffer >>> (bitsInBuffer - 5)) & 0x1F));
                bitsInBuffer -= 5;
            }
        }
        if (bitsInBuffer > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsInBuffer)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] decode(String encoded) {
        String cleaned = encoded.trim().toUpperCase().replace("=", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsInBuffer = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("invalid base32 character: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                out.write((buffer >>> (bitsInBuffer - 8)) & 0xFF);
                bitsInBuffer -= 8;
            }
        }
        return out.toByteArray();
    }
}
