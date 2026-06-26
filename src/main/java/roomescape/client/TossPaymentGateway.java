package roomescape.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import roomescape.client.dto.ConfirmRequest;
import roomescape.client.dto.TossErrorResponse;
import roomescape.client.dto.TossPaymentResponse;
import roomescape.domain.PaymentConfirmation;
import roomescape.domain.PaymentGateway;
import roomescape.domain.PaymentResult;
import roomescape.domain.PaymentStatus;
import roomescape.exception.ErrorCode;
import roomescape.exception.ExternalPaymentException;
import roomescape.exception.PaymentAlreadyProcessedException;
import roomescape.exception.PaymentTimeoutException;

@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

    private final RestClient tossRestClient;
    private final ObjectMapper objectMapper;

    @Retryable(
            retryFor = ExternalPaymentException.class,
            exclude = PaymentAlreadyProcessedException.class,
            maxAttempts = 2,
            backoff = @Backoff(delayExpression = "${toss.read-timeout-ms}", multiplier = 1))
    public PaymentResult confirm(PaymentConfirmation confirmation) {
        try {
            final ConfirmRequest request = new ConfirmRequest(
                    confirmation.paymentKey(), confirmation.orderId(), confirmation.amount());
            final TossPaymentResponse response = tossRestClient.post()
                    .uri("/v1/payments/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", confirmation.orderId())
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        TossErrorResponse error = objectMapper.readValue(res.getBody(), TossErrorResponse.class);
                        throw TossPaymentException.of(res.getStatusCode(), error);
                    })
                    .body(TossPaymentResponse.class);
            return toResult(response);
        } catch (TossPaymentException.AlreadyProcessed e) {
            throw new PaymentAlreadyProcessedException(ErrorCode.PAYMENT_ALREAY_PROCESSED);
        } catch (ResourceAccessException e) {
            throw new PaymentTimeoutException(ErrorCode.PAYMENT_TIMEOUT);
        } catch (RestClientException e) {
            throw new ExternalPaymentException(ErrorCode.PAYMENT_SERVER_ERROR);
        }
    }

    private PaymentResult toResult(TossPaymentResponse response) {
        return new PaymentResult(
                response.paymentKey(),
                response.orderId(),
                PaymentStatus.from(response.status()),
                response.totalAmount()
        );
    }
}
