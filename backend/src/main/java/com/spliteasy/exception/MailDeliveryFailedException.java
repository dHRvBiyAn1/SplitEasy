package com.spliteasy.exception;

/** Transport failures never carry provider diagnostics or message content into logs. */
public class MailDeliveryFailedException extends DeliveryUnavailableException {
  public MailDeliveryFailedException() {
    super("Email delivery is currently unavailable. Try again later.");
  }
}
