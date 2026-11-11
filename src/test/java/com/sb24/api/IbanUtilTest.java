package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sb24.api.util.IbanUtil;
import org.junit.jupiter.api.Test;

class IbanUtilTest {
    @Test
    void generatedIbanIsValid() {
        String iban = IbanUtil.generate("9944", "0001", IbanUtil.randomAccountNumber());
        assertEquals(25, iban.length());
        assertTrue(IbanUtil.isValid(iban));
        assertEquals("9944", IbanUtil.bankCode(iban));
    }

    @Test
    void tamperedIbanIsRejected() {
        String iban = IbanUtil.generate("9944", "0001", "12345678901");
        char last = iban.charAt(24);
        String bad = iban.substring(0, 24) + (last == '0' ? '1' : '0');
        assertFalse(IbanUtil.isValid(bad));
        assertFalse(IbanUtil.isValid("PT50000201231234567890154"));
        assertFalse(IbanUtil.isValid(null));
    }
}
