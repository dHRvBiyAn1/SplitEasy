package com.spliteasy.util;

/** Email helpers. One source of truth for how emails are normalized before lookup/storage. */
public final class Emails {

    private Emails() {}

    /** Trim surrounding whitespace and lowercase, so lookups and storage are consistent. */
    public static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
