package com.example.routermanager.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MacAddressesTest {

    @ParameterizedTest
    @CsvSource({
            "02:00:5E:00:00:01, 02:00:5e:00:00:01",
            "02-00-5e-00-00-01, 02:00:5e:00:00:01",
            "02005e000001,      02:00:5e:00:00:01",
            "'  02:00:5e:00:00:01  ', 02:00:5e:00:00:01"
    })
    @DisplayName("every MAC spelling normalises to lower-case colon form")
    void normalizes(String raw, String expected) {
        assertThat(MacAddresses.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null and empty become the empty string, never null")
    void handlesMissing() {
        assertThat(MacAddresses.normalize(null)).isEmpty();
        assertThat(MacAddresses.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("something that is not a MAC is kept (lower-cased) rather than dropped")
    void keepsUnknownShapes() {
        assertThat(MacAddresses.normalize("NOT-A-MAC")).isEqualTo("not-a-mac");
        assertThat(MacAddresses.isValid("NOT-A-MAC")).isFalse();
        assertThat(MacAddresses.isValid("02:00:5e:00:00:01")).isTrue();
        assertThat(MacAddresses.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("numeric parsing never throws")
    void numbersAreSafe() {
        assertThat(Numbers.parseLongOrNull("42")).isEqualTo(42L);
        assertThat(Numbers.parseLongOrNull("299.91")).isEqualTo(299L);
        assertThat(Numbers.parseLongOrNull("")).isNull();
        assertThat(Numbers.parseLongOrNull(null)).isNull();
        assertThat(Numbers.parseLongOrNull("abc")).isNull();
        assertThat(Numbers.parseLong("abc", 7L)).isEqualTo(7L);
        assertThat(Numbers.parseLongOrNull("3342582966")).isEqualTo(3_342_582_966L);
    }

    @Test
    @DisplayName("sha256(password + token) is the digest the router expects")
    void digestMatchesReference() {
        // Independent reference, produced outside the JVM:
        //   python3 -c "import hashlib; print(hashlib.sha256('secret72704973'.encode()).hexdigest())"
        assertThat(ZteWebClient.sha256Hex("secret" + "72704973"))
                .isEqualTo("95997e33bfcc477f4abc454ddf4311fbb7ae8375711a41c3526fe67a25068db1")
                .matches("[0-9a-f]{64}");
    }
}
