package dev.brighten.anticheat.check.impl.movement.nofall;

import cc.funkemunky.api.tinyprotocol.packet.in.WrappedInFlyingPacket;
import dev.brighten.anticheat.check.api.Check;
import dev.brighten.anticheat.check.api.CheckInfo;
import dev.brighten.anticheat.check.api.CheckType;
import dev.brighten.anticheat.check.api.Packet;

@CheckInfo(name = "NoFall (A)", description = "Checks to make sure the ground packet from the client is legit",
        checkType = CheckType.BADPACKETS, punishVL = 20, executable = false)
public class NoFallA extends Check {

    @Packet
    public void onPacket(WrappedInFlyingPacket packet) {
        if(!packet.isPos()) return;

        boolean flag = data.playerInfo.clientGround
                ? data.playerInfo.deltaY != 0 && !data.playerInfo.serverGround && data.playerInfo.groundTicks > 1
                : data.playerInfo.deltaY == 0 && data.playerInfo.lDeltaY == 0;

        if(!data.playerInfo.flightCancel
                && flag) {
            vl+= data.lagInfo.lagging ? 1 : 3;

            if(vl > 2) {
                flag("ground=" + data.playerInfo.clientGround + " deltaY=" + data.playerInfo.deltaY);
            }
        } else vl-= vl > 0 ? 0.2f : 0;

        debug("ground=" + data.playerInfo.clientGround
                + " deltaY=" + data.playerInfo.deltaY + " vl=" + vl);
    }
}