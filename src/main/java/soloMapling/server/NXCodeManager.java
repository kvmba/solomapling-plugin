package soloMapling.server;

import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static soloMapling.server.SoloMaplingUtilities.random;

public class NXCodeManager {

    /**
     * Creates a new NX code with the specified code string
     *
     * <p>The id is left to the table's AUTO_INCREMENT. It used to be {@code SELECT MAX(id)+1}, which
     * two NX merchants finishing a sale at the same time both read - the second INSERT then hit a
     * duplicate primary key, {@code createCompleteNXCode} returned -1, and the merchant read out a
     * gift code that had never been stored (the player's redemption failed after the trade was
     * already complete).
     *
     * @param code The code string to insert
     * @return The generated ID of the new code entry, or -1 when the insert failed
     */
    public static int createNXCode(String code) {
        int generatedId = -1;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO nxcode (code, retriever, expiration) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, code);
            ps.setNull(2, java.sql.Types.VARCHAR); // completely empty/null
            ps.setLong(3, 8901234567890L); // big expiration time

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return generatedId;
    }

    /**
     * Creates a new NX code item entry linked to an existing code.
     *
     * <p>See {@link #createNXCode}: the id comes from AUTO_INCREMENT, not a read-then-write MAX.
     *
     * @param codeId   The ID of the nxcode entry to link to
     * @param quantity The quantity of NX (default 10000 for 10k NX)
     * @return The generated ID of the new code item entry, or -1 when the insert failed
     */
    public static int createNXCodeItem(int codeId, int quantity) {
        int generatedId = -1;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO nxcode_items (codeid, type, item, quantity) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, codeId);
            ps.setInt(2, 4); // type 4 for prepaid nx
            ps.setInt(3, 0); // item id (not used for NX)
            ps.setInt(4, quantity);

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return generatedId;
    }

    /**
     * Convenience method to create both nxcode and nxcode_items entries in one call
     *
     * @param code     The code string
     * @param quantity The quantity of NX (default 10000 for 10k NX)
     * @return The ID used for both entries, or -1 when either insert failed
     */
    public static int createCompleteNXCode(String code, int quantity) {
        // Store EXACTLY the string the bot will read out to the player.
        //
        // This used to strip the dashes before inserting, while the generated code (and therefore
        // the string spoken/printed at the hand-off) kept them. The host's redemption path
        // (CouponCodeHandler.getNXCodeResult) does an exact-match `SELECT * FROM nxcode WHERE
        // code = ?` on the upper-cased input and does NOT strip dashes, so every code a bot handed
        // out resolved to nothing: the player paid, wrote the code down, and the Cash Shop answered
        // "invalid coupon". Display string == stored string is the invariant that makes a gift card
        // redeemable; the column is VARCHAR(17) and the dashed form is exactly 17 characters.
        int codeId = createNXCode(code);
        if (codeId <= 0) {
            return -1;
        }
        if (createNXCodeItem(codeId, quantity) <= 0) {
            return -1; // a code nobody can redeem is not a delivered code
        }
        return codeId;
    }

    /**
     * Overloaded convenience method with default 10k NX quantity
     *
     * @param code The code string
     * @return The ID used for both entries, or -1 when either insert failed
     */
    public static int createCompleteNXCode(String code) {
        return createCompleteNXCode(code, 10000);
    }

    /**
     * Generates a random gift card code in the format: XXXXX-XXXXX-XXXXX
     * Uses only unambiguous characters (excludes 0, O, I, 1, L etc.)
     *
     * <p>17 characters total (15 alphanumeric + 2 dashes) - exactly the {@code nxcode.code}
     * column width, so the generated string is stored and redeemed verbatim. Keep the length in
     * sync with that column: a longer code would be silently truncated by MySQL and no longer
     * match what the player was told.
     */
    public static String generateGiftCardCode() {
        String characters = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
        StringBuilder codeBuilder = new StringBuilder();

        for (int section = 0; section < 3; section++) {
            if (section > 0) {
                codeBuilder.append("-");
            }

            for (int i = 0; i < 5; i++) {
                int randomIndex = random.nextInt(characters.length());
                codeBuilder.append(characters.charAt(randomIndex));
            }
        }
        return codeBuilder.toString();
    }

}
