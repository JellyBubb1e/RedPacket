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
    // ========================== 核心兼容层 ==========================
    // 修复1：动态获取 CraftBukkit 包路径（适配1.20.5+）
    private static final String CRAFTBUKKIT_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();

    // 修复2：动态解析NMS版本（兼容旧版）
    private static final String nmsName;
    static {
        String[] packageParts = getServer().getClass().getPackage().getName().split("\\.");
        Optional<String> versionPart = Arrays.stream(packageParts)
                .filter(part -> part.startsWith("v"))
                .findFirst();
        if (!versionPart.isPresent()) {
            // 1.20.5+ 没有版本号，尝试从Bukkit版本推导
            String bukkitVersion = Bukkit.getBukkitVersion().split("-")[0];
            String[] versionSegments = bukkitVersion.split("\\.");
            int minorVer = Integer.parseInt(versionSegments[1]);
            nmsName = "v1_%d_R3".formatted(minorVer); // 示例：1.20.5 -> v1_20_R3
        } else {
            nmsName = versionPart.get();
        }
    }

    // 修复3：增强版版本号解析
    private static final int version;
    static {
        String[] versionParts = nmsName.split("_");
        if (versionParts.length < 2) {
            throw new IllegalStateException("Invalid NMS version format: " + nmsName);
        }
        try {
            version = Integer.parseInt(versionParts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Failed to parse version: " + versionParts[1], e);
        }
    }

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
     * 动态获取CraftBukkit类（适配1.20.5+包结构）
     */
    private static Class<?> getCraftBukkitClass(String className) throws ClassNotFoundException {
        return Class.forName(CRAFTBUKKIT_PACKAGE + "." + className);
    }

    /**
     * 动态获取NMS类（兼容旧版）
     */
    private static Class<?> getNMSClass(String className) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + nmsName + "." + className);
    }

    // ========================== 初始化方法 ==========================
    public static void setup() {
        if (version <= 7) {
            RedPacketPlugin.log(Level.SEVERE, "插件只支持1.8+版本！");
            throw new IllegalStateException("Unsupported version: 1." + version);
        }

        try {
            if (version > 12) {
                return; // 高版本使用原生API
            }

            // 初始化NMS类
            entityPlayer = getNMSClass("EntityPlayer");
            chatSerializer = getNMSClass("IChatBaseComponent$ChatSerializer");
            IChatBaseComponent = getNMSClass("IChatBaseComponent");
            PacketPlayOutTitle = getNMSClass("PacketPlayOutTitle");
            PlayerConnection = getNMSClass("PlayerConnection");

            // 初始化CraftBukkit类（适配新包结构）
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
            Optional<Class<?>> enumClass = Arrays.stream(innerClasses)
                    .filter(Class::isEnum)
                    .findFirst();
            if (!enumClass.isPresent()) {
                throw new RuntimeException("Missing EnumTitleAction in PacketPlayOutTitle");
            }
            EnumTitleAction = enumClass.get();
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
        playSound(player, version > 8 ? "ENTITY_PLAYER_LEVELUP" : "LEVEL_UP");
    }

    public static void playMeowSound(Player player) {
        playSound(player, version > 8 ? "ENTITY_CAT_AMBIENT" : "CAT_MEOW");
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
        if (version >= 11) {
            player.sendTitle(title, subtitle, -1, -1, -1);
            return;
        }

        if (version >= 8) {
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
    }

    // -------------------------- JSON消息 --------------------------
    public static void sendJSONMessage(Player player, BaseComponent... components) {
        if (version >= 12) {
            player.spigot().sendMessage(components);
            return;
        }

        if (version >= 7) {
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
}
