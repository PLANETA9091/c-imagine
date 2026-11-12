package dev.cim.live;

import net.minecraft.server.players.PlayerList;

/** The facade server's player list: allocated, never constructed; see {@link FacadeServer#getPlayerList}. */
final class FacadePlayerList extends PlayerList {

    private FacadePlayerList() {
        super(null, null, null, 0);
        throw new AssertionError();
    }
}
