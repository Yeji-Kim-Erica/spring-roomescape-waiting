package roomescape.exception;

public class PaymentAlreadyProcessedException extends BusinessException {
    public PaymentAlreadyProcessedException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
