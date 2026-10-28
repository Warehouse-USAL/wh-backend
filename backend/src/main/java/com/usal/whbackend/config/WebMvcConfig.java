package com.usal.whbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final AuthorizeBeforeBindingInterceptor authorizeBeforeBinding;

  public WebMvcConfig(AuthorizeBeforeBindingInterceptor authorizeBeforeBinding) {
    this.authorizeBeforeBinding = authorizeBeforeBinding;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authorizeBeforeBinding);
  }
}
