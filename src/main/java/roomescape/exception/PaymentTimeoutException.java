package roomescape.exception;

public class PaymentTimeoutException extends BusinessException {
    public PaymentTimeoutException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
