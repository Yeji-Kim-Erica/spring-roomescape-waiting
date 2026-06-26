package roomescape.exception;

public class ExternalPaymentException extends BusinessException {
    public ExternalPaymentException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
