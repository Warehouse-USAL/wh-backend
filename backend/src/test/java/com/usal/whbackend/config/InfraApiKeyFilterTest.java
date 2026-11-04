package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InfraApiKeyFilterTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(InfraApiKeyFilter.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(appender);
  }

  @Test
  void aBlankKeyWarnsAtStartupWithoutLeakingAnything() {
    new InfraApiKeyFilter("");

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("INFRA_API_KEY is not set")
        .contains("/infra/**");
  }

  @Test
  void aConfiguredKeyDoesNotWarn() {
    new InfraApiKeyFilter("a-key");

    assertThat(appender.list).isEmpty();
  }

  @Test
  void aBlankKeyRejectsEvenAnEmptyHeader() throws Exception {
    InfraApiKeyFilter filter = new InfraApiKeyFilter("");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/infra/orders/1/status");
    request.addHeader(InfraApiKeyFilter.HEADER, "");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("INVALID_API_KEY");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void theMatchingKeyPassesAndOtherPathsAreNotFiltered() throws Exception {
    InfraApiKeyFilter filter = new InfraApiKeyFilter("a-key");
    MockHttpServletRequest infra = new MockHttpServletRequest("POST", "/infra/orders/1/status");
    infra.addHeader(InfraApiKeyFilter.HEADER, "a-key");
    MockFilterChain passed = new MockFilterChain();
    filter.doFilter(infra, new MockHttpServletResponse(), passed);
    assertThat(passed.getRequest()).isNotNull();

    MockHttpServletResponse other = new MockHttpServletResponse();
    MockFilterChain otherChain = new MockFilterChain();
    filter.doFilter(new MockHttpServletRequest("GET", "/products"), other, otherChain);
    assertThat(otherChain.getRequest()).isNotNull();
    assertThat(other.getStatus()).isEqualTo(200);
  }
}
