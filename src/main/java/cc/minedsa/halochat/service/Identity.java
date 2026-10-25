package cc.minedsa.halochat.service;

/**
 * Resolved chat identity of the current request.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
public record Identity(Type type, String name, String displayName, String avatar,
                       boolean canManage) {

    public enum Type {
        USER, GUEST, ANONYMOUS
    }

    public static Identity anonymous() {
        return new Identity(Type.ANONYMOUS, null, "游客", null, false);
    }

    public boolean isUser() {
        return type == Type.USER;
    }

    public boolean isGuest() {
        return type == Type.GUEST;
    }

    public boolean isAnonymous() {
        return type == Type.ANONYMOUS;
    }

    public boolean isAuthenticated() {
        return type != Type.ANONYMOUS;
    }

    public Identity withCanManage(boolean value) {
        return new Identity(type, name, displayName, avatar, value);
    }
}
