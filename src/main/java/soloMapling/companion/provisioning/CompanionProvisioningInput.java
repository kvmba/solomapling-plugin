package soloMapling.companion.provisioning;

import java.util.regex.Pattern;

public final class CompanionProvisioningInput {

    // Allowed characters, and nothing else: ASCII letters (A-Z, a-z), ASCII digits (0-9) and
    // simplified-Chinese ideographs (CJK Unified Ideographs, U+4E00-U+9FA5).
    //
    // A whitelist, so it rejects by default rather than by enumeration: kana, full-width forms,
    // CJK punctuation, symbols and every invisible or control character all fail on the same
    // rule instead of needing their own entry.
    //
    // The subtracted set is decorative stroke characters - 丶 丨 灬 丿 乀 亅 彡 乂. They sit inside
    // the CJK block, so a plain [\u4e00-\u9fa5] lets them through, but players use them as
    // name decoration rather than as words, and they render inconsistently in the v83 font.
    private static final String STROKE_DECORATION =
            "\\u4e36\\u4e28\\u706c\\u4e3f\\u4e40\\u4e85\\u5f61\\u4e42";

    private static final Pattern CHARACTER_NAME = Pattern.compile(
            "[a-zA-Z0-9\\u4e00-\\u9fa5&&[^" + STROKE_DECORATION + "]]{2,12}");

    private CompanionProvisioningInput() {
    }

    public static String validateCharacterName(String value) {
        if (value == null || !CHARACTER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "characterName must be 2-12 characters, using only A-Z, a-z, 0-9 or "
                            + "simplified Chinese characters");
        }
        return value;
    }

    public static int parseCharacterId(String value) {
        try {
            int characterId = Integer.parseInt(value);
            if (characterId <= 0) {
                throw new NumberFormatException();
            }
            return characterId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cid must be a positive integer");
        }
    }

    public static long parsePersonaSeed(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("personaSeed must be a signed 64-bit integer");
        }
    }
}
