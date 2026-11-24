package io.github.marinco.aviation.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** Runs the filter and returns the ID the rest of the chain saw in the logging context. */
    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        return seenInChain.get();
    }

    @Test
    void safeInboundIdIsKeptLoggedAndEchoed() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/airports/KJFK");
        request.addHeader(RequestIdFilter.HEADER, "client-abc-123");
        var response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen).isEqualTo("client-abc-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-abc-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull(); // cleared after the request
    }

    /** Anything that could forge or break a log line is replaced, never passed through. */
    @ParameterizedTest
    @ValueSource(strings = {"evil\r\nINFO fake log line", "has space", "semi;colon", "{\"json\":1}", ""})
    void unsafeInboundIdIsReplacedWithUuid(String unsafe) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/airports/KJFK");
        request.addHeader(RequestIdFilter.HEADER, unsafe);
        var response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen).isNotEqualTo(unsafe);
        assertThat(UUID.fromString(seen)).isNotNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen);
    }

    @Test
    void overlongIdIsReplacedButSixtyFourCharsIsKept() throws Exception {
        String max = "a".repeat(64);
        var ok = new MockHttpServletRequest();
        ok.addHeader(RequestIdFilter.HEADER, max);
        assertThat(run(ok, new MockHttpServletResponse())).isEqualTo(max);

        var tooLong = new MockHttpServletRequest();
        tooLong.addHeader(RequestIdFilter.HEADER, max + "a");
        assertThat(run(tooLong, new MockHttpServletResponse())).isNotEqualTo(max + "a");
    }

    @Test
    void missingIdGetsUuid() throws Exception {
        var response = new MockHttpServletResponse();

        String seen = run(new MockHttpServletRequest(), response);

        assertThat(UUID.fromString(seen)).isNotNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen);
    }
}
