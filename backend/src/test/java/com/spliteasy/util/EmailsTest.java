package com.spliteasy.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The shared normalization used by both AuthService (register/login) and GroupService.addMember. */
class EmailsTest {

    @Test
    void trimsAndLowercases() {
        assertThat(Emails.normalize("  Foo@BAR.com ")).isEqualTo("foo@bar.com");
    }

    @Test
    void alreadyNormalizedIsUnchanged() {
        assertThat(Emails.normalize("foo@bar.com")).isEqualTo("foo@bar.com");
    }
}
