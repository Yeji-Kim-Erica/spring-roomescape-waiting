package roomescape.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import roomescape.domain.PaymentConfirmation;
import roomescape.exception.ErrorCode;
import roomescape.exception.ExternalPaymentException;
import roomescape.exception.PaymentTimeoutException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TossClientTimeoutTest {

  // 응답 없는(SYN 무응답) IP → connect 가 매달려 connect timeout 을 유발한다.
  private static final String BLACKHOLE_URL = "http://10.255.255.1:81";

  static MockWebServer mockWebServer;

  static {
    mockWebServer = new MockWebServer();
    try {
      mockWebServer.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String SUCCESS_BODY = """
      {"paymentKey": "test_pk_1", "orderId": "order-1", "status": "DONE", "totalAmount": 10000}
      """;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private TossPaymentGateway tossPaymentGateway;

  @DynamicPropertySource
  static void tossProperties(DynamicPropertyRegistry registry) {
    registry.add("toss.base-url", () -> mockWebServer.url("/").toString());
    registry.add("toss.secret-key", () -> "test_gsk_dummy");
    registry.add("toss.connect-timeout-ms", () -> "500");
    registry.add("toss.read-timeout-ms", () -> "500");
  }

  @AfterAll
  static void tearDown() throws IOException {
    mockWebServer.shutdown();
  }

  private PaymentConfirmation confirmation() {
    return new PaymentConfirmation("test_pk_1", "order-1", 10000L);
  }

  @Test
  void 읽기타임아웃이면_readTimeout만큼만_기다렸다가_1번의_재시도_후_RestClient예외로_실패한다() {
    // given
    int totalAttempts = 2;
    for (int i = 0; i < totalAttempts; i++) {
      mockWebServer.enqueue(new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(SUCCESS_BODY)
              .setHeadersDelay(2, TimeUnit.SECONDS));
    }

    // 다른 테스트가 MockWebServer를 사용했을 수 있으므로 현재까지의 누적 요청 수를 기록합니다.
    int initialRequestCount = mockWebServer.getRequestCount();

    // when
    var start = System.nanoTime();
    assertThatThrownBy(() -> tossPaymentGateway.confirm(confirmation()))
            .isInstanceOf(ExternalPaymentException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_SERVER_ERROR);

    var elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // then
    int actualRequests = mockWebServer.getRequestCount() - initialRequestCount;
    assertThat(actualRequests).isEqualTo(totalAttempts);

    // 총소요 시간 검증:
    // - 시도 1회당: 500ms (read timeout)
    // - 재시도 간격(backoff): 500ms
    // 즉, (500) + (backoff 500 + 500) = 대략 1500ms 소요
    assertThat(elapsedMs).isBetween(1500L, 2500L);
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void 라우팅불가_IP면_connectTimeout만큼_기다렸다가_SocketTimeout으로_실패한다() {
    // 학생이 채운 tossRestClient 의 connect timeout 을 그대로 검증한다.
    // 설정 전(initial)엔 타임아웃이 없어 블랙홀 연결이 매달리므로 @Timeout(3초)이 끊어 실패시킨다.
    var gateway = new TossPaymentGateway(
        new TossClientConfig().tossRestClient(BLACKHOLE_URL, "test_gsk_dummy", 500, 500),
        objectMapper
    );

    var start = System.nanoTime();
    assertThatThrownBy(() -> gateway.confirm(confirmation()))
        .isInstanceOf(PaymentTimeoutException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_TIMEOUT);
    var elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // connect timeout(500ms)만큼 기다렸다가 끊긴다.
    assertThat(elapsedMs).isBetween(300L, 2500L);
  }

}
