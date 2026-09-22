package duyell.ai.tool.declarative;

/**
 * "一组属于某个角色的声明式工具"。
 *
 * <p>存在的意义：让批量迁移**只加一个类**，而不用改注册器。
 * {@code DeclarativeToolRegistrar} 注入 {@code List<DeclarativeToolGroup>}，
 * 按 {@link #role()} 把每个组扫出来的工具注册到对应角色下——
 * 迁教师工具就加一个 `TeacherDeclarativeTools`，迁管理员就再加一个，注册器一行不动。
 *
 * <p>为什么不让扫描器去猜角色（例如看包名或类名）：角色是**权限边界**，
 * 不该由命名约定推导。写错包名就会把教师工具注册成学生工具，
 * 而这类错误的症状是"某个角色多了一个不该有的工具"，很难从测试里看出来——
 * 显式实现 {@link #role()} 让它在编译期就被迫写清楚。
 *
 * @author duyell
 */
public interface DeclarativeToolGroup {

    /** 该组工具的归属角色（student / teacher / admin），与手写注册器里的角色字符串一致 */
    String role();

    /**
     * 该组工具要注册到哪些角色下。
     *
     * <p>默认只注册到 {@link #role()}；**跨角色通用**的工具（例如 M3 的 `search_policy`：
     * 学生、教师、管理员都可能问制度问题）覆写本方法即可，不需要把同一个类写三遍。
     *
     * <p>注意这里仍然要求显式写出角色列表：角色是权限边界，
     * "某工具对所有人开放"必须是一句**看得见的声明**，而不是靠默认值推断出来的。
     */
    default java.util.List<String> roles() {
        return java.util.List.of(role());
    }
}
