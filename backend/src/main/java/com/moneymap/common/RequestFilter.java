package com.moneymap.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public class RequestFilter extends OncePerRequestFilter {
  private final Secrets secrets;
  private final ObjectMapper mapper;
  private final java.util.function.BooleanSupplier recoveryAllows;
  private final RequestAdmission admission;
  private final Logger log = LoggerFactory.getLogger(RequestFilter.class);

  public RequestFilter(Secrets s, ObjectMapper m) {
    this(s, m, () -> s.local);
  }

  public RequestFilter(Secrets s, ObjectMapper m, java.util.function.BooleanSupplier gate) {
    this(s, m, gate, new RequestAdmission(300, 60));
  }

  public RequestFilter(Secrets s, ObjectMapper m, java.util.function.BooleanSupplier gate,
      RequestAdmission admission) {
    secrets = s;
    mapper = m;
    recoveryAllows = gate;
    this.admission = admission;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse w, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    r.setAttribute("requestId", UUID.randomUUID().toString());
    w.setHeader("X-Request-Id", Responses.id(r));
    w.setHeader("Cache-Control", "no-store");
    try {
      HttpServletRequest admitted = r;
      String origin = r.getHeader("Origin");
      if (origin != null && !secrets.origin.equals(origin))
        throw new ApiException(403, "ORIGIN_DENIED");
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod())) {
        if (!secrets.origin.equals(r.getHeader("Origin")))
          throw new ApiException(403, "ORIGIN_DENIED");
        if (r.getContentLengthLong() > 16384) throw new ApiException(413, "PAYLOAD_TOO_LARGE");
        byte[] body = r.getInputStream().readNBytes(16385);
        if (body.length > 16384) throw new ApiException(413, "PAYLOAD_TOO_LARGE");
        admitted =
            new HttpServletRequestWrapper(r) {
              @Override
              public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                  public int read() {
                    return in.read();
                  }

                  public boolean isFinished() {
                    return in.available() == 0;
                  }

                  public boolean isReady() {
                    return true;
                  }

                  public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                  }
                };
              }

              @Override
              public BufferedReader getReader() {
                return new BufferedReader(
                    new InputStreamReader(
                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
              }
            };
      }
      if (!r.getRequestURI().equals("/actuator/health/liveness")) {
        admission.check(!Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod()));
        if (secrets.recoveryRequired
            || (secrets.local && !Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1")
                .contains(r.getRemoteAddr()))
            || !recoveryAllows.getAsBoolean())
          throw new ApiException(503, "RECOVERY_REQUIRED");
      }
      chain.doFilter(admitted, w);
    } catch (ApiException e) {
      r.setAttribute("errorCode", e.code);
      w.setStatus(e.status);
      if (e.status == 429) w.setHeader("Retry-After", "60");
      w.setContentType("application/json");
      mapper.writeValue(w.getOutputStream(), Responses.error(e.code, r));
    } finally {
      Object path = r.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      log.info(
          "requestId={} path={} status={} durationMs={} code={}",
          Responses.id(r),
          path == null ? "unmatched" : path,
          w.getStatus(),
          (System.nanoTime() - start) / 1000000,
          Objects.toString(r.getAttribute("errorCode"), "NONE"));
    }
  }
}
