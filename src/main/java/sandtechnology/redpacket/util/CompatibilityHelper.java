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
import java.util.logging.Level;

import static sandtechnology.redpacket.RedPacketPlugin.getInstance;

public class CompatibilityHelper {
    // 固定NMS版本
    private static final String NMS_VERSION = "v1_21_R3";

    // ========================== NMS反射相关 ==========================
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

    // ========================== 工具方法 ==========================
    /**
     * 获取NMS类
     */
    private static Class<?> getNMSClass(String className) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + NMS_VERSION + "." + className);
    }

    /**
     * 获取CraftBukkit类
     */
    private static Class<?> getCraftBukkitClass(String className) throws ClassNotFoundException {
        return Class.forName("org.bukkit.craftbukkit." + NMS_VERSION + "." + className);
    }

    // ========================== 初始化方法 ==========================
    public static void setup() {
        try {
            // 初始化NMS类
            entityPlayer = getNMSClass("EntityPlayer");
            chatSerializer = getNMSClass("IChatBaseComponent$ChatSerializer");
            IChatBaseComponent = getNMSClass("IChatBaseComponent");
            PacketPlayOutTitle = getNMSClass("PacketPlayOutTitle");
            PlayerConnection = getNMSClass("PlayerConnection");

            // 初始化CraftBukkit类
            craftPlayer = getCraftBukkitClass("entity.CraftPlayer");
            if (craftPlayer == null) {
                throw new ClassNotFoundException("CraftPlayer class not found");
            }

            // 获取核心方法
            getHandle = craftPlayer.getMethod("getHandle");
            sendMessage = entityPlayer.getMethod("sendMessage", IChatBaseComponent);
            toComponent = chatSerializer.getMethod("a", String.class);
            sendPacket = PlayerConnection.getMethod("sendPacket", getNMSClass("Packet"));

            // 初始化标题枚举
            Class<?>[] innerClasses = PacketPlayOutTitle.getClasses();
            EnumTitleAction = Arrays.stream(innerClasses)
                    .filter(Class::isEnum)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Missing EnumTitleAction in PacketPlayOutTitle"));
            EnumTitleActions = (Enum<? extends Enum>[]) EnumTitleAction.getEnumConstants();

            // 构造标题包
            CPacketPlayOutTitle = PacketPlayOutTitle.getConstructor(
                    EnumTitleAction,
                    IChatBaseComponent
            );
        } catch (Exception e) {
            throw new RuntimeException("Compatibility layer initialization failed", e);
        }
    }

    // ========================== 功能方法 ==========================
    private static Object invoke(Method method, Object obj, Object... args) {
        try {
            return method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败: " + method.getName(), e);
        }
    }

    private static Object newInstance(Constructor<?> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("反射实例化失败: " + constructor.getDeclaringClass().getName(), e);
        }
    }

    // -------------------------- 音效相关 --------------------------
    public static void playLevelUpSound(Player player) {
        playSound(player, "ENTITY_PLAYER_LEVELUP");
    }

    public static void playMeowSound(Player player) {
        playSound(player, "ENTITY_CAT_AMBIENT");
    }

    private static void playSound(Player player, String soundName) {
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundName), 100, 1);
        } catch (IllegalArgumentException e) {
            RedPacketPlugin.log(Level.WARNING, "未知音效名称: " + soundName);
        }
    }

    // -------------------------- 标题相关 --------------------------
    public static void sendTitle(Player player, String title, String subtitle) {
        if (Bukkit.getVersion().contains("1.21")) {
            player.sendTitle(title, subtitle, -1, -1, -1);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
            try {
                Object handle = invoke(getHandle, player);
                Object connection = entityPlayer.getField("playerConnection").get(handle);

                Object titleComponent = invoke(toComponent, null,
                        ComponentSerializer.toString(new TextComponent(title)));
                Object subtitleComponent = invoke(toComponent, null,
                        ComponentSerializer.toString(new TextComponent(subtitle)));

                Object titlePacket = newInstance(CPacketPlayOutTitle,
                        EnumTitleActions[0], titleComponent);
                Object subtitlePacket = newInstance(CPacketPlayOutTitle,
                        EnumTitleActions[1], subtitleComponent);

                Bukkit.getScheduler().runTask(getInstance(), () -> {
                    invoke(sendPacket, connection, titlePacket);
                    invoke(sendPacket, connection, subtitlePacket);
                });
            } catch (Exception e) {
                RedPacketPlugin.log(Level.SEVERE, "发送标题时发生错误: " + e.getMessage());
            }
        });
    }

    // -------------------------- JSON消息 --------------------------
    public static void sendJSONMessage(Player player, BaseComponent... components) {
        if (Bukkit.getVersion().contains("1.21")) {
            player.spigot().sendMessage(components);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
            try {
                Object handle = invoke(getHandle, player);
                String json = ComponentSerializer.toString(components);
                Object component = invoke(toComponent, null, json);

                Bukkit.getScheduler().runTask(getInstance(), () -> {
                    invoke(sendMessage, handle, component);
                });
            } catch (Exception e) {
                RedPacketPlugin.log(Level.SEVERE, "发送JSON消息时发生错误: " + e.getMessage());
            }
        });
    }
}
