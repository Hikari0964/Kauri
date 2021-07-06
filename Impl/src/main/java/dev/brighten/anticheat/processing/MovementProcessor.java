package dev.brighten.anticheat.processing;

import cc.funkemunky.api.Atlas;
import cc.funkemunky.api.reflections.impl.MinecraftReflection;
import cc.funkemunky.api.tinyprotocol.api.ProtocolVersion;
import cc.funkemunky.api.tinyprotocol.packet.in.WrappedInFlyingPacket;
import cc.funkemunky.api.utils.*;
import cc.funkemunky.api.utils.handlers.PlayerSizeHandler;
import cc.funkemunky.api.utils.objects.VariableValue;
import cc.funkemunky.api.utils.objects.evicting.EvictingList;
import cc.funkemunky.api.utils.world.CollisionBox;
import cc.funkemunky.api.utils.world.types.RayCollision;
import cc.funkemunky.api.utils.world.types.SimpleCollisionBox;
import dev.brighten.anticheat.Kauri;
import dev.brighten.anticheat.data.ObjectData;
import dev.brighten.anticheat.listeners.api.impl.KeepaliveAcceptedEvent;
import dev.brighten.anticheat.utils.MiscUtils;
import dev.brighten.anticheat.utils.MouseFilter;
import dev.brighten.anticheat.utils.MovementUtils;
import dev.brighten.anticheat.utils.timer.Timer;
import dev.brighten.anticheat.utils.timer.impl.TickTimer;
import lombok.val;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Deque;
import java.util.List;

public class MovementProcessor {
    private final ObjectData data;

    public Deque<Float> yawGcdList = new EvictingList<>(60),
            pitchGcdList = new EvictingList<>(60);
    public float deltaX, deltaY, lastDeltaX, lastDeltaY, smoothYaw, smoothPitch, lsmoothYaw, lsmoothPitch;
    public Tuple<List<Double>, List<Double>> yawOutliers, pitchOutliers;
    public long lastCinematic;
    public float sensitivityX, sensitivityY, yawMode, pitchMode;
    public int sensXPercent, sensYPercent;
    private MouseFilter mxaxis = new MouseFilter(), myaxis = new MouseFilter();
    private float smoothCamFilterX, smoothCamFilterY, smoothCamYaw, smoothCamPitch;
    private Timer lastReset = new TickTimer(2), generalProcess = new TickTimer(3);
    private GameMode lastGamemode;
    public static float offset = (int)Math.pow(2, 24);
    public static double groundOffset = 1 / 64.;
    private static String keepaliveAcceptListener = Kauri.INSTANCE.eventHandler
            .listen(KeepaliveAcceptedEvent.class,  listner -> {
                if(listner.getData().playerInfo.serverGround) {
                    listner.getData().playerInfo.kGroundTicks++;
                    listner.getData().playerInfo.kAirTicks = 0;
                } else {
                    listner.getData().playerInfo.kAirTicks++;
                    listner.getData().playerInfo.kGroundTicks = 0;
                }
    });

    public PotionEffectType levitation = null;

    public MovementProcessor(ObjectData data) {
        this.data = data;

        if(ProtocolVersion.getGameVersion().isOrAbove(ProtocolVersion.V1_8)) {
            try {
                levitation = PotionEffectType.getByName("LEVITATION");
            } catch(Exception e) {

            }
        }
    }

    public void process(WrappedInFlyingPacket packet, long timeStamp) {
        //We check if it's null and intialize the from and to as equal to prevent large deltas causing false positives since there
        //was no previous from (Ex: delta of 380 instead of 0.45 caused by jump jump in location from 0,0,0 to 380,0,0)
        if (data.playerInfo.from == null) {
            data.playerInfo.from
                    = data.playerInfo.to
                    = new KLocation(packet.getX(), packet.getY(), packet.getZ(), packet.getYaw(), packet.getPitch());
        } else {
            data.playerInfo.from = new KLocation(
                    data.playerInfo.to.x,
                    data.playerInfo.to.y,
                    data.playerInfo.to.z,
                    data.playerInfo.to.yaw,
                    data.playerInfo.to.pitch,
                    data.playerInfo.to.timeStamp);
        }

        //We set the to x,y,z like this to prevent inaccurate data input. Because if it isnt a positional packet,
        // it returns getX, getY, getZ as 0.
        if (packet.getX() != 0 || packet.getY() != 0 || packet.getZ() != 0) {
            data.playerInfo.to.x = packet.getX();
            data.playerInfo.to.y = packet.getY();
            data.playerInfo.to.z = packet.getZ();
            //if this is the case, this assumes client movement in between therefore we have to calculate where ground would be.
        } else if(packet.isGround() && !data.playerInfo.clientGround) { //this is the last ground
            val optional = data.blockInfo.belowCollisions.stream()
                    .filter(box -> Math.pow(box.yMax - data.playerInfo.to.y, 2) <= 9.0E-4D && data.box.copy()
                            .offset(0, -.1, 0).isCollided(box)).findFirst();

            if(optional.isPresent()) {
                data.playerInfo.to.y-= data.playerInfo.to.y - optional.get().yMax;
                data.playerInfo.clientGround = data.playerInfo.serverGround = true;
            }
        }

        if(data.playerInfo.serverGround && data.playerInfo.lastMoveCancel.isPassed()) {
            data.playerInfo.setbackLocation = new Location(data.getPlayer().getWorld(),
                    data.playerInfo.to.x, data.playerInfo.to.y, data.playerInfo.to.z,
                    data.playerInfo.to.yaw, data.playerInfo.to.pitch);
        }

        data.playerInfo.to.timeStamp = timeStamp;
        //Adding past location
        data.pastLocation.addLocation(data.playerInfo.to);

        if(data.playerInfo.doingTeleport) data.playerInfo.lastTeleportTimer.reset();

        if (data.playerInfo.posLocs.size() > 0 && packet.isPos()) {
            synchronized (data.playerInfo.posLocs) {
                for (KLocation loc : data.playerInfo.posLocs) {
                    double dx = data.playerInfo.to.x - loc.x,
                            dy = data.playerInfo.to.y - loc.y, dz = data.playerInfo.to.z - loc.z;
                    double delta =  dx * dx + dy * dy + dz * dz;

                    if(delta >= 0.25) continue;

                    data.playerInfo.serverPos = true;
                    data.playerInfo.lastServerPos = timeStamp;
                    data.playerInfo.lastTeleportTimer.reset();
                    data.playerInfo.inventoryOpen = false;
                    data.playerInfo.doingTeleport = false;
                    data.playerInfo.posLocs.remove(loc);

                    break;
                }
            }
        } else if (data.playerInfo.serverPos) {
            data.playerInfo.serverPos = false;
        }

        data.playerInfo.lClientGround = data.playerInfo.clientGround;
        data.playerInfo.clientGround = packet.isGround();
        //Setting the motion delta for use in checks to prevent repeated functions.
        data.playerInfo.lDeltaX = data.playerInfo.deltaX;
        data.playerInfo.lDeltaY = data.playerInfo.deltaY;
        data.playerInfo.lDeltaZ = data.playerInfo.deltaZ;
        data.playerInfo.deltaX = data.playerInfo.to.x - data.playerInfo.from.x;
        data.playerInfo.deltaY = data.playerInfo.to.y - data.playerInfo.from.y;
        data.playerInfo.deltaZ = data.playerInfo.to.z - data.playerInfo.from.z;
        data.playerInfo.lDeltaXZ = data.playerInfo.deltaXZ;
        data.playerInfo.deltaXZ = MathUtils.hypot(data.playerInfo.deltaX, data.playerInfo.deltaZ);

        data.playerInfo.blockOnTo = BlockUtils.getBlock(data.playerInfo.to.toLocation(data.getPlayer().getWorld()));
        data.playerInfo.blockBelow = BlockUtils.getBlock(data.playerInfo.to.toLocation(data.getPlayer().getWorld())
                .subtract(0, 1, 0));

        if(!data.getPlayer().getGameMode().equals(lastGamemode)) data.playerInfo.lastGamemodeTimer.reset();
        lastGamemode = data.getPlayer().getGameMode();
        data.playerInfo.creative = !data.getPlayer().getGameMode().equals(GameMode.SURVIVAL)
                && !data.getPlayer().getGameMode().equals(GameMode.ADVENTURE);

        data.blockInfo.fromFriction = data.blockInfo.currentFriction;
        if(data.playerInfo.blockBelow != null) {
            val mat = XMaterial.requestXMaterial(
                    data.playerInfo.blockBelow.getType().name(), data.playerInfo.blockBelow.getData());

            if (mat != null)
                data.blockInfo.currentFriction = BlockUtils.getFriction(mat);
        }

        if(packet.isPos()) {
            //We create a separate from BoundingBox for the predictionService since it should operate on pre-motion data.
            data.box = PlayerSizeHandler.instance.bounds(data.getPlayer(),
                    data.playerInfo.to.x, data.playerInfo.to.y, data.playerInfo.to.z);

            if(timeStamp - data.creation > 400L) data.blockInfo.runCollisionCheck(); //run b4 everything else for use below.
        }

        if(data.playerInfo.calcVelocityY > 0) {
            data.playerInfo.calcVelocityY-= 0.08f;
            data.playerInfo.calcVelocityY*= 0.98f;
        } else data.playerInfo.calcVelocityY = 0;

        if(Math.abs(data.playerInfo.calcVelocityX) > 0.005) {
            data.playerInfo.calcVelocityX*= data.playerInfo.lClientGround
                    ? data.blockInfo.currentFriction * 0.91f : 0.91f;
        } else data.playerInfo.calcVelocityX = 0;

        if(Math.abs(data.playerInfo.calcVelocityZ) > 0.005) {
            data.playerInfo.calcVelocityZ*= data.playerInfo.lClientGround
                    ? data.blockInfo.currentFriction * 0.91f : 0.91f;
        } else data.playerInfo.calcVelocityZ = 0;

        synchronized (data.playerInfo.velocities) {
            for (Vector velocity : data.playerInfo.velocities) {
                if(Math.abs(velocity.getY() - data.playerInfo.deltaY) < 0.01) {
                    if(data.playerInfo.doingVelocity) {
                        data.playerInfo.lastVelocity.reset();

                        data.playerInfo.doingVelocity = false;
                        data.playerInfo.lastVelocityTimestamp = System.currentTimeMillis();
                        data.predictionService.velocity = true;
                        data.playerInfo.velocityX = data.playerInfo.calcVelocityX = (float) velocity.getX();
                        data.playerInfo.velocityY = data.playerInfo.calcVelocityY = (float) velocity.getY();
                        data.playerInfo.velocityZ = data.playerInfo.calcVelocityZ = (float) velocity.getZ();
                    }
                    data.playerInfo.velocities.remove(velocity);
                    break;
                }
            }
        }

        if(packet.isPos() || packet.isLook()) {
            KLocation origin = data.playerInfo.to.clone();
            origin.y+= data.playerInfo.sneaking ? 1.54f : 1.62f;
            RayCollision collision = new RayCollision(origin.toVector(), MathUtils.getDirection(origin));

            data.playerInfo.lookingAtBlock = collision
                    .boxesOnRay(data.getPlayer().getWorld(),
                            data.getPlayer().getGameMode().equals(GameMode.CREATIVE) ? 6.0 : 5.0).size() > 0;
        }

        data.playerInfo.inVehicle = data.getPlayer().getVehicle() != null;
        data.playerInfo.gliding = PlayerUtils.isGliding(data.getPlayer());
        data.playerInfo.riptiding = Atlas.getInstance().getBlockBoxManager()
                .getBlockBox().isRiptiding(data.getPlayer());
        /* We only set the jumpheight on ground since there's no need to check for it while they're in the air.
         * If we did check while it was in the air, there would be false positives in the checks that use it. */
        if (packet.isGround() || data.playerInfo.serverGround || data.playerInfo.lClientGround) {
            data.playerInfo.jumpHeight = MovementUtils.getJumpHeight(data);
            data.playerInfo.totalHeight = MovementUtils.getTotalHeight(data.playerVersion,
                    (float)data.playerInfo.jumpHeight);
        }

        if(!data.playerInfo.worldLoaded)
            data.playerInfo.lastChunkUnloaded.reset();


        data.lagInfo.lagging = data.lagInfo.lagTicks.subtract() > 0
                || !data.playerInfo.worldLoaded
                || timeStamp - Kauri.INSTANCE.lastTick >
                new VariableValue<>(110, 60, ProtocolVersion::isPaper).get();

        if(data.playerInfo.insideBlock = (data.playerInfo.blockOnTo != null && data.playerInfo.blockOnTo.getType()
                .equals(XMaterial.AIR.parseMaterial()))) {
            data.playerInfo.lastInsideBlock.reset();
        }

        //We set the yaw and pitch like this to prevent inaccurate data input. Like above, it will return both pitch
        //and yaw as 0 if it isnt a look packet.
        if(packet.isLook()) {
            data.playerInfo.to.yaw = packet.getYaw();
            data.playerInfo.to.pitch = packet.getPitch();
        }

        //Setting the angle delta for use in checks to prevent repeated functions.
        data.playerInfo.lDeltaYaw = data.playerInfo.deltaYaw;
        data.playerInfo.lDeltaPitch = data.playerInfo.deltaPitch;
        data.playerInfo.deltaYaw = data.playerInfo.to.yaw
                - data.playerInfo.from.yaw;
        data.playerInfo.deltaPitch = data.playerInfo.to.pitch - data.playerInfo.from.pitch;
        if (packet.isLook()) {

            data.playerInfo.lastPitchGCD = data.playerInfo.pitchGCD;
            data.playerInfo.lastYawGCD = data.playerInfo.yawGCD;
            data.playerInfo.yawGCD = MiscUtils.gcd((int) (Math.abs(data.playerInfo.deltaYaw) * offset),
                    (int) (Math.abs(data.playerInfo.lDeltaYaw) * offset));
            data.playerInfo.pitchGCD = MiscUtils.gcd((int) (Math.abs(data.playerInfo.deltaPitch) * offset),
                    (int) (Math.abs(data.playerInfo.lDeltaPitch) * offset));

            val origin = data.playerInfo.to.clone();

            origin.y+= data.playerInfo.sneaking ? 1.54 : 1.62;

            if(data.playerInfo.lastTeleportTimer.isPassed(1)) {
                float yawGcd = MathUtils.round(data.playerInfo.yawGCD / offset, 5),
                        pitchGcd = MathUtils.round(data.playerInfo.pitchGCD / offset, 5);

                //Adding gcd of yaw and pitch.
                if (data.playerInfo.yawGCD > 160000 && data.playerInfo.yawGCD < 10500000)
                    yawGcdList.add(yawGcd);
                if (data.playerInfo.pitchGCD > 160000 && data.playerInfo.pitchGCD < 10500000)
                    pitchGcdList.add(pitchGcd);

                if (yawGcdList.size() > 3 && pitchGcdList.size() > 3) {

                    //Making sure to get shit within the std for a more accurate result.
                    if (lastReset.isPassed()) {
                        yawMode = MathUtils.getMode(yawGcdList);
                        pitchMode = MathUtils.getMode(pitchGcdList);
                        yawOutliers = MiscUtils.getOutliers(yawGcdList);
                        pitchOutliers = MiscUtils.getOutliers(pitchGcdList);
                        lastReset.reset();
                        sensXPercent = sensToPercent(sensitivityX = getSensitivityFromYawGCD(yawMode));
                        sensYPercent = sensToPercent(sensitivityY = getSensitivityFromPitchGCD(pitchMode));
                    }


                    lastDeltaX = deltaX;
                    lastDeltaY = deltaY;
                    deltaX = getExpiermentalDeltaX(data);
                    deltaY = getExpiermentalDeltaY(data);

                    if ((data.playerInfo.pitchGCD < 1E5 || data.playerInfo.yawGCD < 1E5) && smoothCamFilterY < 1E6
                            && smoothCamFilterX < 1E6 && timeStamp - data.creation > 1000L) {
                        float f = sensitivityX * 0.6f + .2f;
                        float f1 = f * f * f * 8;
                        float f2 = deltaX * f1;
                        float f3 = deltaY * f1;

                        smoothCamFilterX = mxaxis.smooth(smoothCamYaw, .05f * f1);
                        smoothCamFilterY = myaxis.smooth(smoothCamPitch, .05f * f1);

                        this.smoothCamYaw += f2;
                        this.smoothCamPitch += f3;

                        f2 = smoothCamFilterX * 0.5f;
                        f3 = smoothCamFilterY * 0.5f;

                        //val clampedFrom = (Math.abs(data.playerInfo.from.yaw) > 360 ? data.playerInfo.from.yaw % 360 : data.playerInfo.from.yaw);
                        val clampedFrom = MathUtils.yawTo180F(data.playerInfo.from.yaw);
                        float pyaw = clampedFrom + f2 * .15f;
                        float ppitch = data.playerInfo.from.pitch - f3 * .15f;

                        this.lsmoothYaw = smoothYaw;
                        this.lsmoothPitch = smoothPitch;
                        this.smoothYaw = pyaw;
                        this.smoothPitch = ppitch;

                        float yaccel = Math.abs(data.playerInfo.deltaYaw) - Math.abs(data.playerInfo.lDeltaYaw),
                                pAccel = Math.abs(data.playerInfo.deltaPitch) - Math.abs(data.playerInfo.lDeltaPitch);

                        if (MathUtils.getDelta(smoothYaw, clampedFrom) > (yaccel > 0 ? (yaccel > 10 ? 3 : 2) : 0.1)
                                || MathUtils.getDelta(smoothPitch, data.playerInfo.from.pitch) > (pAccel > 0 ? (yaccel > 10 ? 3 : 2) : 0.1)) {
                            smoothCamYaw = smoothCamPitch = 0;
                            data.playerInfo.cinematicMode = false;
                            mxaxis.reset();
                            myaxis.reset();
                        } else data.playerInfo.cinematicMode = true;

                        //MiscUtils.testMessage("pyaw=" + pyaw + " ppitch=" + ppitch + " yaw=" + data.playerInfo.to.yaw + " pitch=" + data.playerInfo.to.pitch);
                    } else {
                        mxaxis.reset();
                        myaxis.reset();
                        data.playerInfo.cinematicMode = false;
                    }
                }
            } else {
                yawGcdList.clear();
                pitchGcdList.clear();
            }
        } else {
            smoothCamYaw = smoothCamPitch = 0;
        }

        if (packet.isPos()) {
            if (data.playerInfo.serverGround && data.playerInfo.groundTicks > 4)
                data.playerInfo.groundLoc = data.playerInfo.to;
        }

        //Fixes glitch when logging in.
        //We use the NMS (bukkit) version since their state is likely saved in a player data file in the world.
        //This should prevent false positives from ability inaccuracies.
        if (timeStamp - data.creation < 500L) {
            if (data.playerInfo.canFly != data.getPlayer().getAllowFlight()) {
                data.playerInfo.lastToggleFlight.reset();
            }
            data.playerInfo.canFly = data.getPlayer().getAllowFlight();
            data.playerInfo.flying = data.getPlayer().isFlying();
        }

        data.playerInfo.serverAllowedFlight = data.getPlayer().getAllowFlight();
        if (data.playerInfo.breakingBlock) data.playerInfo.lastBrokenBlock.reset();

        //Setting fallDistance
        if (!data.playerInfo.serverGround
                && data.playerInfo.deltaY < 0
                && !data.blockInfo.onClimbable
                && !data.blockInfo.inLiquid
                && !data.blockInfo.inWeb) {
            data.playerInfo.fallDistance += -data.playerInfo.deltaY;
        } else data.playerInfo.fallDistance = 0;

        //Running jump check
        if (!data.playerInfo.clientGround) {
            if (!data.playerInfo.jumped && data.playerInfo.lClientGround
                    && data.playerInfo.deltaY >= 0) {
                data.playerInfo.jumped = true;
            } else {
                data.playerInfo.inAir = true;
                data.playerInfo.jumped = false;
            }
        } else data.playerInfo.jumped = data.playerInfo.inAir = false;

        /* General Block Info */

        //Setting if players were on blocks when on ground so it can be used with checks that check air things.
        if (data.playerInfo.serverGround || data.playerInfo.clientGround || data.playerInfo.collided) {
            data.playerInfo.wasOnIce = data.blockInfo.onIce;
            data.playerInfo.wasOnSlime = data.blockInfo.onSlime;
        }

        if((data.playerInfo.onLadder = MovementUtils.isOnLadder(data))
                && (data.playerInfo.deltaY <= 0 || data.blockInfo.collidesHorizontally)) {
            data.playerInfo.isClimbing = true;
        }

        //Checking if the player was collided with ghost blocks.
        synchronized (data.ghostBlocks) {
            SimpleCollisionBox boxToCheck = data.box.copy().expand(0.4f);
            for (Location location : data.ghostBlocks.keySet()) {
                if(location.toVector().distanceSquared(data.playerInfo.to.toVector()) > 25) continue;

                if(data.ghostBlocks.get(location).isCollided(boxToCheck)) {
                    data.playerInfo.lastGhostCollision.reset();
                    break;
                }
            }
        }

        //Checking if user is in liquid.
        if (data.blockInfo.inLiquid) data.playerInfo.liquidTimer.reset();
        //Half block ticking (slabs, stairs, bed, cauldron, etc.)
        if (data.blockInfo.onHalfBlock) data.playerInfo.lastHalfBlock.reset();
        //We dont check if theyre still on ice because this would be useless to checks that check a player in air too.
        if (data.blockInfo.onIce) data.playerInfo.iceTimer.reset();
        if (data.blockInfo.inWeb) data.playerInfo.webTimer.reset();
        if (data.blockInfo.onClimbable) data.playerInfo.climbTimer.reset();
        if (data.blockInfo.onSlime) data.playerInfo.slimeTimer.reset();
        if (data.blockInfo.onSoulSand) data.playerInfo.soulSandTimer.reset();
        if (data.blockInfo.blocksAbove) data.playerInfo.blockAboveTimer.reset();
        if (data.blockInfo.collidedWithEntity) data.playerInfo.lastEntityCollision.reset();

        //Player ground/air positioning ticks.
        if (!data.playerInfo.clientGround) {
            data.playerInfo.airTicks++;
            data.playerInfo.groundTicks = 0;
        } else {
            data.playerInfo.groundTicks++;
            data.playerInfo.airTicks = 0;
        }

        data.playerInfo.baseSpeed = MovementUtils.getBaseSpeed(data);
        /* General Cancel Booleans */
        boolean hasLevi = levitation != null && data.potionProcessor.hasPotionEffect(levitation);

        data.playerInfo.generalCancel = data.getPlayer().getAllowFlight()
                || data.playerInfo.creative
                || hasLevi
                || data.getPlayer().isSleeping()
                || data.playerInfo.lastGhostCollision.isNotPassed()
                || data.playerInfo.doingTeleport
                || data.playerInfo.lastTeleportTimer.isNotPassed(1)
                || data.playerInfo.riptiding
                || data.playerInfo.gliding
                || data.playerInfo.lastPlaceLiquid.isNotPassed(5)
                || data.playerInfo.inVehicle
                || (data.playerInfo.lastChunkUnloaded.isNotPassed(35)
                && MathUtils.getDelta(-0.098, data.playerInfo.deltaY) < 0.0001)
                || timeStamp - data.playerInfo.lastRespawn < 2500L
                || data.playerInfo.lastToggleFlight.isNotPassed(40)
                || timeStamp - data.creation < 4000
                || Kauri.INSTANCE.lastTickLag.isNotPassed(5);

        data.playerInfo.flightCancel = data.playerInfo.generalCancel
                || data.playerInfo.webTimer.isNotPassed(8)
                || data.playerInfo.liquidTimer.isNotPassed(8)
                || data.playerInfo.onLadder
                || (data.playerInfo.deltaXZ == 0 && data.playerInfo.deltaY == 0)
                || data.blockInfo.roseBush
                || data.playerInfo.doingVelocity
                || data.playerInfo.lastVelocity.isNotPassed(3)
                || data.playerInfo.slimeTimer.isNotPassed(8)
                || data.playerInfo.climbTimer.isNotPassed(6)
                || data.playerInfo.lastHalfBlock.isNotPassed(5);
    }
    private static float getDeltaX(float yawDelta, float gcd) {
        return MathHelper.ceiling_float_int(yawDelta / gcd);
    }

    private static float getDeltaY(float pitchDelta, float gcd) {
        return MathHelper.ceiling_float_int(pitchDelta / gcd);
    }

    public static float getExpiermentalDeltaX(ObjectData data) {
        float deltaPitch = data.playerInfo.deltaYaw;
        float sens = data.moveProcessor.sensitivityX;
        float f = sens * 0.6f + .2f;
        float calc = f * f * f * 8;

        float result = deltaPitch / (calc * .15f);

        return result;
    }

    public static float getExpiermentalDeltaY(ObjectData data) {
        float deltaPitch = data.playerInfo.deltaPitch;
        float sens = data.moveProcessor.sensitivityY;
        float f = sens * 0.6f + .2f;
        float calc = f * f * f * 8;

        float result = deltaPitch / (calc * .15f);

        return result;
    }

    public static int sensToPercent(float sensitivity) {
        return MathHelper.floor_float(sensitivity / .5f * 100);
    }

    public static float percentToSens(int percent) {
        return percent * .0070422534f;
    }

    //Noncondensed
    /*private static double getSensitivityFromYawGCD(double gcd) {
        double stepOne = yawToF2(gcd) / 8;
        double stepTwo = Math.cbrt(stepOne);
        double stepThree = stepTwo - .2f;
        return stepThree / .6f;
    }*/

    //Condensed
    public static float getSensitivityFromYawGCD(float gcd) {
        return ((float)Math.cbrt(yawToF2(gcd) / 8f) - .2f) / .6f;
    }

    //Noncondensed
    /*private static double getSensitivityFromPitchGCD(double gcd) {
        double stepOne = pitchToF3(gcd) / 8;
        double stepTwo = Math.cbrt(stepOne);
        double stepThree = stepTwo - .2f;
        return stepThree / .6f;
    }*/

    //Condensed
    private static float getSensitivityFromPitchGCD(float gcd) {
        return ((float)Math.cbrt(pitchToF3(gcd) / 8f) - .2f) / .6f;
    }

    private static float getF1FromYaw(float gcd) {
        float f = getFFromYaw(gcd);

        return f * f * f * 8;
    }

    private static float getFFromYaw(float gcd) {
        float sens = getSensitivityFromYawGCD(gcd);
        return sens * .6f + .2f;
    }

    private static float getFFromPitch(float gcd) {
        float sens = getSensitivityFromPitchGCD(gcd);
        return sens * .6f + .2f;
    }

    private static float getF1FromPitch(float gcd) {
        float f = getFFromPitch(gcd);

        return (float)Math.pow(f, 3) * 8;
    }

    private static float yawToF2(float yawDelta) {
        return yawDelta / .15f;
    }

    private static float pitchToF3(float pitchDelta) {
        int b0 = pitchDelta >= 0 ? 1 : -1; //Checking for inverted mouse.
        return (pitchDelta / b0) / .15f;
    }

}
