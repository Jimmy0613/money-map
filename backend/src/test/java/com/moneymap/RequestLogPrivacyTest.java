package com.moneymap;
import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

class RequestLogPrivacyTest {
  @Test void requestLogOmitsBodyQueryCookiesAndSensitivePathValues() throws Exception {
    byte[] a=new byte[32],b=new byte[32]; Arrays.fill(b,(byte)1);
    var secrets=new Secrets(Base64.getEncoder().encodeToString(a),Base64.getEncoder().encodeToString(b),"https://example.invalid",true,new MockEnvironment());
    var logger=(Logger)LoggerFactory.getLogger(RequestFilter.class);
    var capture=new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      var request=new MockHttpServletRequest("POST","/api/v1/transactions/private-path-marker");
      request.addHeader("Origin","https://example.invalid");
      request.setQueryString("query=private-query-marker&latitude=12.345678");
      request.setCookies(new Cookie("mm_access","private-cookie-marker"));
      request.setContent("{\"email\":\"private-email-marker@example.invalid\",\"memo\":\"private-body-marker\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/v1/transactions/{id}");
      new RequestFilter(secrets,new ObjectMapper(), () -> true).doFilter(request,new MockHttpServletResponse(),(r,w)->{});
      String log=capture.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
      assertTrue(log.contains("path=/api/v1/transactions/{id}"));
      assertTrue(log.contains("status=200"));
      for(String marker:List.of("private-path-marker","private-query-marker","12.345678","private-cookie-marker","private-email-marker","private-body-marker")) assertFalse(log.contains(marker));
    } finally { logger.detachAppender(capture); capture.stop(); }
  }
}
