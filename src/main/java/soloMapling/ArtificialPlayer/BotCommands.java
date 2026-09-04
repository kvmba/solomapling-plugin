package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.Environment.BotMessages;

import org.gms.util.Randomizer;

public class BotCommands {

    // Bot commands
    // how do I want to call bot commands in code?
    // BotCommand.BotSpeak(20005, "Hey");
    // currentBot.BotSpeak("Hey"); // reference's itself // probably wont work for multi bot systems
    // BotSpeak(20005, "Hey");

    /*
    BotCommands - things bots do that are visible in game itself
     */

    public static int[] botDealerRollDoubleDice(Character fakechar) {
        int skillid = 5320007; // Cannon Master Double Down
        int level = 5;
        final int dice1 = Randomizer.nextInt(6) + 1;
        final int dice2 = Randomizer.nextInt(6) + 1;
        // The dice roll is announced to everyone on the map (DiceBot), so it is player-visible
        // copy, not a debug trace.
        SocialCommands.BotSpeak(fakechar, BotMessages.get("dice.roll", dice1, dice2));
//        fakechar.getMap().broadcastMessage(fakechar, PacketCreator.EffectPacket.showDiceEffect(fakechar.getId(), skillid, dice1, -1, level), false);
//        fakechar.getMap().broadcastMessage(fakechar, PacketCreator.EffectPacket.showDoubleDiceEffect(fakechar.getId(), skillid, dice2, -1, level), false);
        return new int[]{dice1, dice2};
    }


}
