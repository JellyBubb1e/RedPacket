package sandtechnology.redpacket.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import sandtechnology.redpacket.RedPacketPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static org.bukkit.Bukkit.getServer;
import static sandtechnology.redpacket.RedPacketPlugin.getInstance;

public class CompatibilityHelper {
    // 修复1：动态查找包含"v"前缀的NMS版本字段
    private static final String nmsName;
    static {
        String[] packageParts = getServer().getClass().getPackage().getName().split("\\.");
        Optional<String> versionPart = Arrays.stream(packageParts)
                .filter(part -> part.startsWith("v"))
                .findFirst();
        if (!versionPart.isPresent()) {
            throw new IllegalStateException("无法解析NMS版本，请确认服务器版本兼容性");
        }
        nmsName = versionPart.get();
    }

    // 修复2：添加版本解析容错
    private static final int version;
    static {
        String[] versionParts = nmsName.split("_");
        if (versionParts.length < 2) {
            throw new IllegalStateException("无效的NMS版本格式: " + nmsName);
        }
        try {
            version = Integer.parseInt(versionParts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("无法解析版本号: " + versionParts[1], e);
        }
    }

    private static Class<?> IChatBaseComponent;
    private static Class<?> chatSerializer;
    private static Class<?> craftPlayer;
    private static Class<?> entityPlayer;
    private static Class<?> PacketPlayOutTitle;
    private static Class<?> EnumTitleAction;
    private static Class<?> PlayerConnection;
    private static Enum<? extends Enum>[] EnumTitleActions;
    private static Method getHandle;
    private static Method sendMessage;
    private static Method toComponent;
    private static Method sendPacket;
    private static Constructor<?> CPacketPlayOutTitle;

    private CompatibilityHelper() {
    }

    private static Class<?> getNMSClass(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + nmsName + "." + name);
    }

    public static void setup() {
        if (version <= 7) {
            RedPacketPlugin.log(Level.SEVERE, "插件只支持1.8+版本！");
            throw new IllegalStateException("插件只支持1.8+版本！");
        }
        try {
            if (version > 12) {
                return;
            }
            // 修复3：添加空安全检查
            entityPlayer = getNMSClass("EntityPlayer");
            chatSerializer = getNMSClass("IChatBaseComponent$ChatSerializer");
            IChatBaseComponent = getNMSClass("IChatBaseComponent");
            PacketPlayOutTitle = getNMSClass("PacketPlayOutTitle");
            PlayerConnection = getNMSClass("PlayerConnection");
            craftPlayer = Class.forName("org.bukkit.craftbukkit." + nmsName + ".entity.CraftPlayer");

            // 修复4：增强反射方法的健壮性
            if (craftPlayer == null) {
                throw new ClassNotFoundException("CraftPlayer class not found");
            }
            getHandle = craftPlayer.getMethod("getHandle");

            sendMessage = entityPlayer.getMethod("sendMessage", IChatBaseComponent);
            toComponent = chatSerializer.getMethod("a", String.class);
            sendPacket = PlayerConnection.getMethod("sendPacket", getNMSClass("Packet"));

            // 修复5：处理可能缺失的枚举类型
            Class<?>[] innerClasses = PacketPlayOutTitle.getClasses();
            Optional<Class<?>> enumClass = Arrays.stream(innerClasses)
                    .filter(Class::isEnum)
                    .findFirst();
            if (!enumClass.isPresent()) {
                throw new RuntimeException("PacketPlayOutTitle中未找到枚举类型");
            }
            EnumTitleAction = enumClass.get();
            EnumTitleActions = (Enum<? extends Enum>[]) EnumTitleAction.getEnumConstants();

            CPacketPlayOutTitle = PacketPlayOutTitle.getConstructor(EnumTitleAction, IChatBaseComponent);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("插件兼容层初始化失败", e);
        }
    }

    private static Object invoke(Method method, Object obj, Object... objs) {
        try {
            return method.invoke(obj, objs);
        } catch (Exception e) {
            throw new RuntimeException("在反射调用方法时发生错误！" + method.getName(), e);
        }
    }

    private static Object newInstance(Constructor constructor, Object... objs) {
        try {
            return constructor.newInstance(objs);
        } catch (Exception e) {
            throw new RuntimeException("在反射实例化类时发生错误！类名：", e);
        }
    }

    public static void playLevelUpSound(Player player) {
        if (version > 8) {
            playSound(player, "ENTITY_PLAYER_LEVELUP");
        } else {
            playSound(player, "LEVEL_UP");
        }
    }

    public static void playMeowSound(Player player) {
        if (version > 8) {
            playSound(player, "ENTITY_CAT_AMBIENT");
        } else {
            playSound(player, "CAT_MEOW");
        }
    }

    private static void playSound(Player player, String name) {
        player.playSound(player.getLocation(), Sound.valueOf(name), 100, 1);
    }

    private static Object getDeclaredFieldAndGetIt(Class<?> target, String field, Object instance) {
        try {
            return target.getDeclaredField(field).get(instance);
        } catch (Exception e) {
            throw new RuntimeException("在反射获取字段时发生错误！方法名：", e);
        }
    }

    public static void sendTitle(Player player, String title, String subtitle) {
        if (version >= 11) {
            player.sendTitle(title, subtitle, -1, -1, -1);
        } else {
            if (version >= 8) {
                //反射需要较长时间，采取异步处理再发送消息
                Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
                    Object connectionInstance = getDeclaredFieldAndGetIt(entityPlayer, "playerConnection", invoke(getHandle, player));
                    Object titlePacket = newInstance(CPacketPlayOutTitle, EnumTitleActions[0], invoke(toComponent, null, ComponentSerializer.toString(new TextComponent(title))));
                    Object subtitlePacket = newInstance(CPacketPlayOutTitle, EnumTitleActions[1], invoke(toComponent, null, ComponentSerializer.toString(new TextComponent(subtitle))));
                    Bukkit.getScheduler().runTask(getInstance(), () -> {
                        invoke(sendPacket, connectionInstance, titlePacket);
                        invoke(sendPacket, connectionInstance, subtitlePacket);
                    });
                });
            }
        }
    }

    public static void sendJSONMessage(Player player, BaseComponent... components) {
        if (version >= 12) {
            player.spigot().sendMessage(components);
        } else {
            if (version >= 7) {
                //反射需要较长时间，采取异步处理再发送消息
                Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
                    Object playerInstance = invoke(getHandle, player);
                    Object JSONString = invoke(toComponent, null, ComponentSerializer.toString(components));
                    Bukkit.getScheduler().runTask(getInstance(), () -> invoke(sendMessage, playerInstance, JSONString));
                });
            }
        }
    }
}
