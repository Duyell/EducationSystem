package utils;

/**
 * @author duyell
 * 业务异常：预期内的可恢复错误（如"密码错误""名额已满"），消息可直接展示给用户。
 * 由 GlobalExceptionHandler 统一捕获，返回 Result 且不打印堆栈。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
