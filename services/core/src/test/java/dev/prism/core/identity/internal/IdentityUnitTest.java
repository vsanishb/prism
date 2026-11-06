package dev.prism.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prism.core.platform.web.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityUnitTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsALongPassphrase() {
        assertThatCode(() -> policy.validate("correct horse battery staple", "user@synthetic.prism.test"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTheMaximumLengthAndRefusesBeyondIt() {
        String longest = "abcdefgh".repeat(16);
        assertThatCode(() -> policy.validate(longest, "user@synthetic.prism.test")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(longest + "x", "user@synthetic.prism.test"))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "password1234", "PASSWORD1234", "aaaaaaaaaaaaaaaa", "ababababababab"})
    void refusesWeakPasswords(String password) {
        assertThatThrownBy(() -> policy.validate(password, "user@synthetic.prism.test"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesPasswordsContainingTheEmailName() {
        assertThatThrownBy(() -> policy.validate("my-priya.sharma-secret", "priya.sharma@synthetic.prism.test"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void blockDurationGrowsAndIsCapped() {
        assertThat(LoginThrottle.blockDuration(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottle.blockDuration(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(LoginThrottle.blockDuration(100)).isEqualTo(Duration.ofMinutes(15));
    }
}
