package dev.brighten.anticheat.utils;

import cc.funkemunky.api.utils.KLocation;
import dev.brighten.anticheat.Kauri;
import dev.brighten.anticheat.data.ObjectData;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;

import java.util.*;
import java.util.stream.Collectors;

public class PastLocation {
    public final LinkedList<KLocation> previousLocations = new LinkedList<>();

    public KLocation getPreviousLocation(int time) {
        synchronized (previousLocations) {
            return (this.previousLocations.stream()
                    .min(Comparator.comparing(loc -> Math.abs(time - loc.timeStamp)))
                    .orElse(this.previousLocations.getFirst()));
        }
    }

    public List<KLocation> getEstimatedLocation(int currentTime, int ping, int delta) {
        synchronized (previousLocations) {
            int tick = currentTime - ping;

            List<KLocation> locs = new ArrayList<>();

            for (KLocation previousLocation : previousLocations) {
                if (Math.abs(tick - previousLocation.timeStamp) <= delta) {
                    locs.add(previousLocation.clone());
                }
            }
            return locs;
        }
    }

    public List<KLocation> getEstimatedLocationByIndex(int ping, int minAdd, int maxAdd) {
        synchronized (previousLocations) {

            List<KLocation> locs = new ArrayList<>();

            int size = previousLocations.size() - 1;
            int max = Math.min(size, previousLocations.size() - ping + maxAdd),
                    min = Math.max(0, size - Math.max(1, ping) - minAdd);

            for (int i = max; i > min; i--) {
                locs.add(previousLocations.get(i));
            }
            return locs;
        }
    }

    public List<KLocation> getEstimatedLocation(long time, long ping) {
        synchronized (previousLocations) {
            return this.previousLocations.stream()
                    .filter(loc -> time - loc.timeStamp > 0
                            && time - loc.timeStamp <= ping + (ping < 50 ? 100 : 50))
                    .collect(Collectors.toList());
        }
    }

    public List<KLocation> getPreviousRange(long delta) {
        synchronized (previousLocations) {
            long stamp = System.currentTimeMillis();

            return this.previousLocations.stream()
                    .filter(loc -> stamp - loc.timeStamp < delta)
                    .collect(Collectors.toList());
        }
    }

    public void addLocation(Location location) {
        synchronized (previousLocations) {
            if (previousLocations.size() >= 20)
                previousLocations.removeFirst();

            KLocation loc = new KLocation(location);
            loc.timeStamp = Kauri.INSTANCE.keepaliveProcessor.tick;
            previousLocations.add(loc);
        }
    }

    public Deque<KLocation> getPreviousLocations() {
        return previousLocations;
    }

    public void addLocation(KLocation location) {
        KLocation loc = location.clone();
        loc.timeStamp = Kauri.INSTANCE.keepaliveProcessor.tick;
        synchronized (previousLocations) {
            previousLocations.add(loc);
        }
    }
}