package config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import utils.BusinessException;
import utils.Result;

import java.util.Objects;

/**
 * @author duyell
 * 全局异常处理：业务异常直接提示用户；系统异常返回通用提示，不泄露内部细节（SQL 等）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的错误，提示用户即可，不打印堆栈 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error("500", e.getMessage());
    }

    /** 缺少必要请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getMessage());
        return Result.error("400", "缺少必要参数: " + e.getParameterName());
    }

    /** 非法参数值 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Result.error("400", e.getMessage());
    }

    /** 参数校验失败（@Valid 启用后生效） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.error("400", msg);
    }

    /** 请求体不可读（JSON 格式错误等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error("400", "请求体格式错误");
    }

    /**
     * 路径不存在 / 方法用错端点：**必须回 4xx，不能回 500**。
     *
     * <p>为什么单独立这两条（2026-09-28 由 MCP 客户端 Cursor 连不上暴露）：
     * 在此之前它们会被下面的 {@code Exception} 兜底成 **HTTP 200 + {"code":"500"}**，
     * 于是"你把消息 POST 到了 SSE 端点"这种**客户端用错接口**的事，
     * 在日志和响应里看起来都像"服务器内部错误"。真实后果不止是难查：
     * MCP 规范里"客户端先试 streamable HTTP、**拿到 4xx 就回退到 SSE**"这条回退路径，
     * 正是靠状态码触发的——我们回 500 等于告诉客户端"服务器坏了"，
     * 它就不回退了，最终表现为 Cursor 里一句信息量为零的 `connect_failure`。
     * （Cursor 的行为日志：`Transient error connecting to streamableHttp server` +
     * JSON-RPC 校验报错，因为它把我们的 `{"code":"500"...}` 当成了 JSON-RPC 响应。）
     *
     * <p>注意本项目的其它接口仍按既有约定"HTTP 200 + 体内 code 表意"，
     * 这里破例用真实状态码，是因为**这两个错误在语义上就是传输层的**
     * （路径不对、方法不对），而框架/客户端都按状态码判断，体里的 code 它们看不到。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("路径不存在: {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error("404", "接口不存在: " + e.getResourcePath()));
    }

    /** 方法不支持：405 并把框架给出的 {@code Allow} 头透出去 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("方法不支持: {}（支持: {}）", e.getMethod(), e.getSupportedHttpMethods());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            builder.allow(e.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(Result.error("405", "该接口不支持 " + e.getMethod() + " 方法"));
    }

    /** 系统异常：打印完整堆栈便于排查，但不向前端泄露内部细节 */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntime(RuntimeException e) {
        log.error("系统异常: ", e);
        return Result.error("500", "系统繁忙，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未知异常: ", e);
        return Result.error("500", "服务器内部错误");
    }
}
