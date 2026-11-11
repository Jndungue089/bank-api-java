package com.sb24.api.util;

import java.math.BigInteger;
import java.security.SecureRandom;

/** Angolan IBAN: AO + 2 check digits + 21 digits (bank 4, branch 4, account 11, national check 2). */
public final class IbanUtil {
    private static final SecureRandom RNG = new SecureRandom();
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private IbanUtil() {}

    public static String randomAccountNumber() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 11; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    public static String generate(String bank, String branch, String account11) {
        String base = bank + branch + account11;
        int nib = 98 - mod97(base + "00");
        String bban = base + String.format("%02d", nib);
        int check = 98 - mod97(bban + "AO00");
        return "AO" + String.format("%02d", check) + bban;
    }

    public static boolean isValid(String iban) {
        if (iban == null || !iban.matches("^AO\\d{23}$")) return false;
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    public static String bankCode(String iban) {
        return iban.substring(4, 8);
    }

    private static int mod97(String alnum) {
        StringBuilder digits = new StringBuilder();
        for (char c : alnum.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else digits.append(Character.getNumericValue(c)); // A=10 ... Z=35
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue();
    }
}
