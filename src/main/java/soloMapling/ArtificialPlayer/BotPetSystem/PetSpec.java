package soloMapling.ArtificialPlayer.BotPetSystem;

/**
 * One pet to be created for a bot, produced by {@link BotPetAssigner} and
 * consumed by {@link BotPetFactory}/{@link BotPetController}.
 *
 * @param itemId      the {@code 500xxxx} pet item id
 * @param level       pet level (1..5); kept low on purpose (see the plan)
 * @param tameness    closeness (0..100), rolled from the owner's level
 * @param fullness    fullness (60..100), so a pet never looks starving
 * @param named       whether the pet gets a random name + name-tag gear
 * @param pickupItem  whether the pet gets the item-pouch gear (loots items)
 * @param pickupMeso  whether the pet gets the meso-magnet gear (loots mesos)
 */
public record PetSpec(int itemId, int level, int tameness, int fullness,
                      boolean named, boolean pickupItem, boolean pickupMeso) {
}
