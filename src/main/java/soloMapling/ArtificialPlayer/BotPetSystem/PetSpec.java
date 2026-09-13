package soloMapling.ArtificialPlayer.BotPetSystem;

/**
 * One pet to be created for a bot, produced by {@link BotPetAssigner} and
 * consumed by {@link BotPetFactory}/{@link BotPetController}.
 *
 * @param itemId      the {@code 500xxxx} pet item id
 * @param level       pet level (1..5); kept low on purpose (see the plan)
 * @param named       whether the pet gets a random name + name-tag gear
 * @param pickupItem  whether the pet gets the item-pouch gear (loots items)
 * @param pickupMeso  whether the pet gets the meso-magnet gear (loots mesos)
 */
public record PetSpec(int itemId, int level, boolean named, boolean pickupItem, boolean pickupMeso) {
}
