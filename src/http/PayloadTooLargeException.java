package http;

public class PayloadTooLargeException extends Exception {
    public PayloadTooLargeException() {
        super("Request body exceeds maximum size");
    }
}
