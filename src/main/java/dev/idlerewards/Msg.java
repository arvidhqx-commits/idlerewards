package dev.idlerewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class Msg {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Msg() {}

    static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        // Legacy codes win over the angle-bracket heuristic: '&7<&bVIP&7>' is
        // decoration, not a MiniMessage tag (portfolio-wide fix, 04.09.2026).
        if (!hasLegacyCode(raw) && raw.indexOf('<') >= 0 && raw.indexOf('>') > raw.indexOf('<')) {
            try {
                return MINI.deserialize(raw);
            } catch (Exception ignored) {
            }
        }
        return LEGACY.deserialize(raw);
    }

    /** Every character that may follow '&' / section sign in a legacy code. */
    private static final String LEGACY_CODES = "0123456789abcdefklmnorxABCDEFKLMNORX";

    /** True if the text carries at least one real legacy colour/format code. */
    private static boolean hasLegacyCode(String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '\u00A7') && LEGACY_CODES.indexOf(s.charAt(i + 1)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
