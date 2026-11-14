package com.spliteasy.attachment;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Replaces Boot's 1 MB default so a 5 MB file reaches the service, which owns the 413. */
@Configuration
class AttachmentConfig {
  @Bean
  MultipartConfigElement multipartConfigElement() {
    var factory = new MultipartConfigFactory();
    factory.setMaxFileSize(DataSize.ofBytes(AttachmentService.MAX_BYTES));
    factory.setMaxRequestSize(DataSize.ofBytes(AttachmentService.MAX_BYTES + 1024 * 1024));
    return factory.createMultipartConfig();
  }
}
