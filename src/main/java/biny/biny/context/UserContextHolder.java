package biny.biny.context;

import biny.biny.model.entity.User;

/**
 * 当前请求登录用户上下文（ThreadLocal）
 */
public final class UserContextHolder {

    private static final ThreadLocal<User> USER_HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(User user) {
        USER_HOLDER.set(user);
    }

    public static User get() {
        return USER_HOLDER.get();
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}

