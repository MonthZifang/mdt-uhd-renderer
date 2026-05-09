package com.mdt.renderer;

import arc.util.CommandHandler;
import arc.util.Log;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public final class UhdRendererPlugin extends Plugin {
    private static final String CONFIG_DIR_NAME = "mdt-uhd-renderer";
    private static final String CONFIG_FILE_NAME = "uhd-renderer.properties";
    private static final Gson GSON = new Gson();

    private static volatile RendererApi api;

    private File dataRoot;
    private RendererConfig config;

    public static RendererApi getApi() {
        return api;
    }

    @Override
    public void init() {
        try {
            dataRoot = resolveDataRoot();
            ensureDefaultResources(dataRoot);
            reload();
            api = new RendererApi(this);
            Log.info("MDT UHD Renderer loaded. config=@", new File(dataRoot, CONFIG_FILE_NAME).getAbsolutePath());
        } catch (Exception exception) {
            throw new RuntimeException("MDT UHD Renderer 初始化失败。", exception);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("uhd-render-reload", "重新加载渲染配置。", args -> {
            try {
                reload();
                Log.info("UHD 渲染配置已重载。");
            } catch (Exception exception) {
                Log.err("UHD 渲染配置重载失败。");
                Log.err(exception);
            }
        });

        handler.register("uhd-render-json", "<json>", "按 JSON 调用渲染器。默认关闭，需配置允许外部调用。", args -> {
            if (!config.allowExternalCommand) {
                Log.warn("当前配置未开放 uhd-render-json。");
                return;
            }
            try {
                RenderRequest request = GSON.fromJson(args[0], RenderRequest.class);
                if (request == null) {
                    Log.warn("请求为空。");
                    return;
                }
                render(request);
                Log.info("已执行外部渲染请求: comId=@ title=@", request.comId, request.title);
            } catch (Throwable throwable) {
                Log.err("执行外部渲染请求失败。");
                Log.err(throwable);
            }
        });
    }

    private synchronized void reload() throws Exception {
        config = RendererConfig.load(new File(dataRoot, CONFIG_FILE_NAME));
    }

    private void render(RenderRequest request) {
        if (!config.enabled || request == null) {
            return;
        }

        float duration = request.durationSeconds <= 0f ? 3600f : request.durationSeconds;
        int align = config.defaultAlign;
        String message = buildMessage(request.title, request.content);

        if ("0".equals(String.valueOf(request.comId))) {
            renderForAllPlayers(message, duration, align, request);
            renderMapLabel(request.content, duration, request.mapX, request.mapY);
            return;
        }

        Player player = resolvePlayerByComId(request.comId);
        if (player == null) {
            Log.warn("未找到在线玩家 com id=@，本次只尝试地图标签渲染。", request.comId);
            renderMapLabel(request.content, duration, request.mapX, request.mapY);
            return;
        }

        showPlayerPopup(player, message, duration, align, request.playerScreenX, request.playerScreenY, request.windowWidth, request.windowHeight);
        renderMapLabel(request.content, duration, request.mapX, request.mapY);
    }

    private void renderForAllPlayers(String message, float duration, int align, RenderRequest request) {
        for (Player player : Groups.player) {
            showPlayerPopup(player, message, duration, align, request.playerScreenX, request.playerScreenY, request.windowWidth, request.windowHeight);
        }
    }

    private void showPlayerPopup(Player player, String message, float duration, int align, int screenX, int screenY, int width, int height) {
        try {
            Class<?> callClass = Class.forName("mindustry.gen.Call");
            Object con = player.con;
            int top = Math.max(0, screenY);
            int left = Math.max(0, screenX);
            int bottom = Math.max(0, height);
            int right = Math.max(0, width);

            if (invokeOptional(callClass, "infoPopup", new Class<?>[] {
                con.getClass(), String.class, String.class, float.class, int.class, int.class, int.class, int.class, int.class
            }, new Object[] {con, message, "mdt-uhd", Float.valueOf(duration), Integer.valueOf(align), Integer.valueOf(top), Integer.valueOf(left), Integer.valueOf(bottom), Integer.valueOf(right)})) {
                return;
            }

            if (invokeOptional(callClass, "infoPopup", new Class<?>[] {
                con.getClass(), String.class, float.class, int.class, int.class, int.class, int.class, int.class
            }, new Object[] {con, message, Float.valueOf(duration), Integer.valueOf(align), Integer.valueOf(top), Integer.valueOf(left), Integer.valueOf(bottom), Integer.valueOf(right)})) {
                return;
            }

            player.sendMessage(message);
        } catch (Throwable throwable) {
            Log.warn("弹窗渲染失败，回退聊天消息: @", throwable.toString());
            player.sendMessage(message);
        }
    }

    private void renderMapLabel(String content, float duration, float mapX, float mapY) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }

        try {
            Class<?> callClass = Class.forName("mindustry.gen.Call");
            if (!invokeOptional(callClass, "label", new Class<?>[] {
                String.class, float.class, float.class, float.class
            }, new Object[] {content, Float.valueOf(duration), Float.valueOf(mapX), Float.valueOf(mapY)})) {
                Log.warn("当前 Mindustry 环境未找到地图标签调用。");
            }
        } catch (Throwable throwable) {
            Log.warn("地图标签渲染失败: @", throwable.toString());
        }
    }

    private boolean invokeOptional(Class<?> owner, String name, Class<?>[] parameterTypes, Object[] args) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.invoke(null, args);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    private Player resolvePlayerByComId(String comId) {
        String uuid = resolveUuidByComId(comId);
        if (uuid == null) {
            return null;
        }
        for (Player player : Groups.player) {
            if (uuid.equalsIgnoreCase(resolveUuid(player))) {
                return player;
            }
        }
        return null;
    }

    private String resolveUuidByComId(String comId) {
        try {
            Class<?> pluginClass = Class.forName(config.listDataPluginClass);
            Method bindListMethod = pluginClass.getMethod("defaultBindListName");
            Object bindList = bindListMethod.invoke(null);
            String bindListName = bindList == null ? "player_bind" : bindList.toString();

            Method getObjectMethod = pluginClass.getMethod("getObject", String.class, String.class);
            Object object = getObjectMethod.invoke(null, bindListName, comId);
            if (object instanceof Map) {
                Object value = ((Map<?, ?>) object).get(config.bindUuidField);
                return value == null ? null : value.toString();
            }
        } catch (Exception exception) {
            Log.err("通过 com id 查询绑定 UUID 失败。");
            Log.err(exception);
        }
        return null;
    }

    private String resolveUuid(Player player) {
        try {
            Method method = player.getClass().getMethod("uuid");
            Object value = method.invoke(player);
            if (value != null) {
                return value.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // fallback below
        }
        try {
            Field field = player.getClass().getField("uuid");
            Object value = field.get(player);
            if (value != null) {
                return value.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // ignored
        }
        return null;
    }

    private String buildMessage(String title, String content) {
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        if (safeTitle.isEmpty()) {
            return safeContent;
        }
        if (safeContent.isEmpty()) {
            return "[accent]" + safeTitle + "[]";
        }
        return "[accent]" + safeTitle + "[]\n" + safeContent;
    }

    private File resolveDataRoot() {
        return new File(new File(new File("config"), "mods"), "config" + File.separator + CONFIG_DIR_NAME);
    }

    private void ensureDefaultResources(File root) throws IOException {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("无法创建配置目录: " + root.getAbsolutePath());
        }
        copyIfMissing(root, CONFIG_FILE_NAME);
    }

    private void copyIfMissing(File root, String resourceName) throws IOException {
        File target = new File(root, resourceName);
        if (target.exists()) {
            return;
        }
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("缺少默认资源: " + resourceName);
            }
            Files.copy(inputStream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class RendererApi {
        private final UhdRendererPlugin plugin;

        private RendererApi(UhdRendererPlugin plugin) {
            this.plugin = plugin;
        }

        public void render(String comId, String title, String content, float durationSeconds, boolean allowManualClose, int windowWidth, int windowHeight, int playerScreenX, int playerScreenY, float mapX, float mapY) {
            RenderRequest request = new RenderRequest();
            request.comId = comId;
            request.title = title;
            request.content = content;
            request.durationSeconds = durationSeconds <= 0f ? plugin.config.defaultDurationSeconds : durationSeconds;
            request.allowManualClose = allowManualClose;
            request.windowWidth = windowWidth <= 0 ? plugin.config.defaultWindowWidth : windowWidth;
            request.windowHeight = windowHeight <= 0 ? plugin.config.defaultWindowHeight : windowHeight;
            request.playerScreenX = playerScreenX;
            request.playerScreenY = playerScreenY;
            request.mapX = mapX;
            request.mapY = mapY;
            plugin.render(request);
        }
    }

    public static final class RenderRequest {
        public String comId;
        public String title;
        public String content;
        public float durationSeconds;
        public boolean allowManualClose = true;
        public int windowWidth;
        public int windowHeight;
        public int playerScreenX;
        public int playerScreenY;
        public float mapX;
        public float mapY;
    }

    private static final class RendererConfig {
        private final boolean enabled;
        private final boolean allowExternalCommand;
        private final float defaultDurationSeconds;
        private final boolean defaultAllowManualClose;
        private final int defaultWindowWidth;
        private final int defaultWindowHeight;
        private final int defaultPlayerScreenX;
        private final int defaultPlayerScreenY;
        private final float defaultMapX;
        private final float defaultMapY;
        private final int defaultAlign;
        private final String listDataPluginClass;
        private final String bindUuidField;

        private RendererConfig(Properties properties) {
            this.enabled = readBoolean(properties, "renderer.enabled", true);
            this.allowExternalCommand = readBoolean(properties, "renderer.allowExternalCommand", false);
            this.defaultDurationSeconds = readFloat(properties, "renderer.defaultDurationSeconds", 6f);
            this.defaultAllowManualClose = readBoolean(properties, "renderer.defaultAllowManualClose", true);
            this.defaultWindowWidth = readInt(properties, "renderer.defaultWindowWidth", 420);
            this.defaultWindowHeight = readInt(properties, "renderer.defaultWindowHeight", 180);
            this.defaultPlayerScreenX = readInt(properties, "renderer.defaultPlayerScreenX", 0);
            this.defaultPlayerScreenY = readInt(properties, "renderer.defaultPlayerScreenY", 0);
            this.defaultMapX = readFloat(properties, "renderer.defaultMapX", 0f);
            this.defaultMapY = readFloat(properties, "renderer.defaultMapY", 0f);
            this.defaultAlign = readInt(properties, "renderer.defaultAlign", 1);
            this.listDataPluginClass = read(properties, "storage.listDataPluginClass", "com.mdt.listdata.ListDataSystemPlugin");
            this.bindUuidField = read(properties, "storage.bindUuidField", "playerUuid");
        }

        private static RendererConfig load(File file) throws IOException {
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return new RendererConfig(properties);
        }

        private static String read(Properties properties, String key, String fallback) {
            String value = properties.getProperty(key);
            return value == null ? fallback : value.trim();
        }

        private static boolean readBoolean(Properties properties, String key, boolean fallback) {
            return Boolean.parseBoolean(read(properties, key, String.valueOf(fallback)));
        }

        private static int readInt(Properties properties, String key, int fallback) {
            try {
                return Integer.parseInt(read(properties, key, String.valueOf(fallback)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static float readFloat(Properties properties, String key, float fallback) {
            try {
                return Float.parseFloat(read(properties, key, String.valueOf(fallback)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
