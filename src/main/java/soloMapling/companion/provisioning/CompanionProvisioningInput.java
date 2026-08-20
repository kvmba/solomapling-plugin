package soloMapling.companion.provisioning;

import java.util.regex.Pattern;

public final class CompanionProvisioningInput {

    private static final Pattern CHARACTER_NAME =
            Pattern.compile("[a-zA-Z0-9\\u4e00-\\u9fa5]{2,12}");

    private CompanionProvisioningInput() {
    }

    public static String validateCharacterName(String value) {
        if (value == null || !CHARACTER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "characterName must be 2-12 letters, digits, or CJK characters");
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
