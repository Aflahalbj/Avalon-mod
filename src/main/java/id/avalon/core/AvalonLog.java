package id.avalon.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class AvalonLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AvalonLog() {}

    public static void info(String msg) {
        LOGGER.info("[Avalon] " + msg);
    }

    public static void warn(String msg) {
        LOGGER.warn("[Avalon] " + msg);
    }

    public static void error(String msg, Throwable t) {
        LOGGER.error("[Avalon] " + msg, t);
    }
}
