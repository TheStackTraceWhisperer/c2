package com.ffxi.viewer.environment;

import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.ZoneEnvironmentResource;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Retail {@code S2F_Environment} ({@code 0x2F}) lookup and diurnal interpolation table built from a
 * zone DAT's {@code weat/<tag>/<HHMM>} and area override ({@code <area>/<tag>/<HHMM>}) hierarchy.
 */
public final class ZoneEnvironmentTable {

    private static final float MINUTES_PER_DAY = 24.0f * 60.0f;
    private static final float RETAIL_TWO_PI = 6.283f;
    private static final float RETAIL_PI = 3.1415f;

    private static final Set<String> WEATHER_TAGS = Set.of(
            "fine", "suny", "clod", "mist", "dryw", "heat", "rain", "squl", "dust", "sand",
            "wind", "stom", "snow", "bliz", "thdr", "bolt", "aura", "ligt", "fogd", "dark"
    );

    public record TimedEnvironmentKey(int minuteOfDay, ZoneEnvironmentResource resource) {
    }

    /**
     * Resolved lighting, sky dome, fog, and clear parameters for a specific zone/area, weather tag, and Vana'diel time.
     * Note that {@code bgLight*Dir} and {@code actorLightDir} point <b>toward</b> the light source so shaders
     * compute {@code max(dot(N, L), 0)}, whereas {@code sunTravelDir} and {@code moonTravelDir} store travel directions
     * (+Y = Down) so orbital celestial bodies lie at {@code -travelDir * 900}.
     */
    public record ResolvedEnvironment(
            boolean indoor,
            float minuteOfDay,
            String weatherTag,
            Vector3f sunTravelDir,
            Vector3f moonTravelDir,
            Vector3f bgLight0Dir,
            Vector3f bgLight0Color,
            Vector3f bgLight1Dir,
            Vector3f bgLight1Color,
            Vector3f bgAmbientColor,
            Vector3f actorLightDir,
            Vector3f actorLightColor,
            Vector3f actorAmbientColor,
            Vector3f fogColor,
            float fogStart,
            float fogEnd,
            Vector3f clearColor,
            float drawDist,
            boolean hasDome,
            int spokeCount,
            float skyRadius,
            Vector3f[] skyColors,
            float[] skyStops
    ) {
    }

    private final Map<String, List<TimedEnvironmentKey>> zoneKeys;
    private final Map<String, Map<String, List<TimedEnvironmentKey>>> areaKeys;

    private ZoneEnvironmentTable(
            Map<String, List<TimedEnvironmentKey>> zoneKeys,
            Map<String, Map<String, List<TimedEnvironmentKey>>> areaKeys
    ) {
        this.zoneKeys = zoneKeys;
        this.areaKeys = areaKeys;
    }

    public static ZoneEnvironmentTable fromDirectory(DirectoryResource root) {
        Map<String, List<TimedEnvironmentKey>> rawZone = new HashMap<>();
        Map<String, Map<String, List<TimedEnvironmentKey>>> rawAreas = new HashMap<>();
        if (root != null) {
            List<String> dirStack = new ArrayList<>();
            collectRecursive(root, true, dirStack, rawZone, rawAreas);
        }

        Map<String, List<TimedEnvironmentKey>> sortedZone = sortKeysMap(rawZone);
        Map<String, Map<String, List<TimedEnvironmentKey>>> sortedAreas = new HashMap<>();
        for (var entry : rawAreas.entrySet()) {
            sortedAreas.put(entry.getKey(), sortKeysMap(entry.getValue()));
        }
        return new ZoneEnvironmentTable(Collections.unmodifiableMap(sortedZone), Collections.unmodifiableMap(sortedAreas));
    }

    private static void collectRecursive(
            DirectoryResource dir,
            boolean isRootWrapper,
            List<String> stack,
            Map<String, List<TimedEnvironmentKey>> rawZone,
            Map<String, Map<String, List<TimedEnvironmentKey>>> rawAreas
    ) {
        String id = sanitizeId(dir.sectionId());
        boolean pushed = !isRootWrapper && !"__root__".equals(id) && !id.isEmpty();
        if (pushed) {
            stack.add(id);
        }
        for (ResourceNode child : dir.children()) {
            if (child instanceof DirectoryResource subDir) {
                collectRecursive(subDir, false, stack, rawZone, rawAreas);
            } else if (child instanceof ZoneEnvironmentResource env) {
                Integer minute = parseHhMmMinutes(sanitizeId(env.sectionId()));
                if (minute == null) {
                    continue;
                }
                int n = stack.size();
                if (n == 4 && "weat".equals(stack.get(2)) && !"weat".equals(stack.get(1))) {
                    String areaId = stack.get(1);
                    String tag = stack.get(3);
                    rawAreas.computeIfAbsent(areaId, ignored -> new HashMap<>())
                            .computeIfAbsent(tag, ignored -> new ArrayList<>())
                            .add(new TimedEnvironmentKey(minute, env));
                } else if (n >= 2 && "weat".equals(stack.get(n - 2))) {
                    String tag = stack.get(n - 1);
                    rawZone.computeIfAbsent(tag, ignored -> new ArrayList<>())
                            .add(new TimedEnvironmentKey(minute, env));
                } else if (n == 3 && !"weat".equals(stack.get(1)) && WEATHER_TAGS.contains(stack.get(2))) {
                    String areaId = stack.get(1);
                    String tag = stack.get(2);
                    rawAreas.computeIfAbsent(areaId, ignored -> new HashMap<>())
                            .computeIfAbsent(tag, ignored -> new ArrayList<>())
                            .add(new TimedEnvironmentKey(minute, env));
                }
            }
        }
        if (pushed) {
            stack.remove(stack.size() - 1);
        }
    }

    private static Map<String, List<TimedEnvironmentKey>> sortKeysMap(Map<String, List<TimedEnvironmentKey>> raw) {
        Map<String, List<TimedEnvironmentKey>> out = new HashMap<>(raw.size());
        for (var entry : raw.entrySet()) {
            List<TimedEnvironmentKey> list = new ArrayList<>(entry.getValue());
            list.sort(Comparator.comparingInt(TimedEnvironmentKey::minuteOfDay));
            out.put(entry.getKey(), Collections.unmodifiableList(list));
        }
        return out;
    }

    public boolean isEmpty() {
        return zoneKeys.isEmpty() && areaKeys.isEmpty();
    }

    public Set<String> availableWeatherTags() {
        return Collections.unmodifiableSet(zoneKeys.keySet());
    }

    public Set<String> availableAreaIds() {
        return Collections.unmodifiableSet(areaKeys.keySet());
    }

    public Optional<ResolvedEnvironment> resolve(String weatherTag, float minuteOfDay, String areaId) {
        String normWeather = sanitizeId(weatherTag);
        String normArea = sanitizeId(areaId);

        if (!normArea.isEmpty()) {
            Map<String, List<TimedEnvironmentKey>> areaMap = areaKeys.get(normArea);
            if (areaMap != null && !areaMap.isEmpty()) {
                Optional<ResolvedEnvironment> resolvedArea = resolveFromMap(areaMap, normWeather, minuteOfDay);
                if (resolvedArea.isPresent()) {
                    return resolvedArea;
                }
            }
        }
        return resolveFromMap(zoneKeys, normWeather, minuteOfDay);
    }

    private static Optional<ResolvedEnvironment> resolveFromMap(
            Map<String, List<TimedEnvironmentKey>> map,
            String weatherTag,
            float minuteOfDay
    ) {
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        String resolvedTag = weatherTag;
        List<TimedEnvironmentKey> keys = !resolvedTag.isEmpty() ? map.get(resolvedTag) : null;
        if (keys == null || keys.isEmpty()) {
            resolvedTag = "suny";
            keys = map.get(resolvedTag);
        }
        if (keys == null || keys.isEmpty()) {
            resolvedTag = "fine";
            keys = map.get(resolvedTag);
        }
        if (keys == null || keys.isEmpty()) {
            var first = map.entrySet().iterator().next();
            resolvedTag = first.getKey();
            keys = first.getValue();
        }
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }

        float now = positiveMod(minuteOfDay, MINUTES_PER_DAY);
        int i = -1;
        for (int idx = keys.size() - 1; idx >= 0; idx--) {
            if (keys.get(idx).minuteOfDay() <= now) {
                i = idx;
                break;
            }
        }
        if (i < 0) {
            i = keys.size() - 1;
        }
        int targetMinute = keys.get(i).minuteOfDay();
        for (int idx = 0; idx <= i; idx++) {
            if (keys.get(idx).minuteOfDay() == targetMinute) {
                i = idx;
                break;
            }
        }

        int j = 0;
        for (int idx = 0; idx < keys.size(); idx++) {
            if (keys.get(idx).minuteOfDay() > now) {
                j = idx;
                break;
            }
        }

        float p = keys.get(i).minuteOfDay();
        float n = keys.get(j).minuteOfDay();
        float span = positiveMod(n - p, MINUTES_PER_DAY);
        float into = positiveMod(now - p, MINUTES_PER_DAY);
        float t = (span == 0.0f) ? 0.0f : (into / span);

        ZoneEnvironmentResource interpolated = lerpEnvironment(keys.get(i).resource(), keys.get(j).resource(), t);
        return Optional.of(toResolvedEnvironment(interpolated, resolvedTag, now));
    }

    private static ZoneEnvironmentResource lerpEnvironment(
            ZoneEnvironmentResource prev,
            ZoneEnvironmentResource next,
            float t
    ) {
        if (t <= 0.0f || prev == next) {
            return prev;
        }
        if (t >= 1.0f) {
            return next;
        }
        boolean indoor = prev.isIndoor() || next.isIndoor();
        ZoneEnvironmentResource.LightGroup actor = lerpLightGroup(prev.actorGroup(), next.actorGroup(), t, indoor);
        ZoneEnvironmentResource.LightGroup bg = lerpLightGroup(prev.bgGroup(), next.bgGroup(), t, indoor);

        int[] prevSky = prev.skyRgba();
        int[] nextSky = next.skyRgba();
        int[] skyRgba = new int[ZoneEnvironmentResource.SKY_SLICE_COUNT * 4];
        for (int k = 0; k < skyRgba.length; k++) {
            skyRgba[k] = lerpByte(prevSky[k], nextSky[k], t);
        }

        float[] prevStops = prev.skyStops();
        float[] nextStops = next.skyStops();
        float[] skyStops = new float[ZoneEnvironmentResource.SKY_SLICE_COUNT];
        for (int k = 0; k < skyStops.length; k++) {
            skyStops[k] = prevStops[k] + (nextStops[k] - prevStops[k]) * t;
        }

        return new ZoneEnvironmentResource(
                prev.sectionId(),
                prev.sectionType(),
                prev.sectionStart(),
                prev.sectionSize(),
                prev.flags() | next.flags(),
                actor,
                bg,
                lerpByte(prev.clearR(), next.clearR(), t),
                lerpByte(prev.clearG(), next.clearG(), t),
                lerpByte(prev.clearB(), next.clearB(), t),
                lerpByte(prev.clearA(), next.clearA(), t),
                prev.drawDist() + (next.drawDist() - prev.drawDist()) * t,
                prev.hasDome(),
                prev.spokeCount(),
                prev.skyRadius() + (next.skyRadius() - prev.skyRadius()) * t,
                skyRgba,
                skyStops
        );
    }

    private static ZoneEnvironmentResource.LightGroup lerpLightGroup(
            ZoneEnvironmentResource.LightGroup a,
            ZoneEnvironmentResource.LightGroup b,
            float t,
            boolean indoor
    ) {
        int moonR = lerpByte(a.moonR(), b.moonR(), t);
        int moonG = lerpByte(a.moonG(), b.moonG(), t);
        int moonB = lerpByte(a.moonB(), b.moonB(), t);
        int moonA = lerpByte(a.moonA(), b.moonA(), t);
        if (indoor) {
            int[] turned = turnSignedDirection(
                    a.moonR(), a.moonG(), a.moonB(),
                    b.moonR(), b.moonG(), b.moonB(),
                    t
            );
            moonR = turned[0];
            moonG = turned[1];
            moonB = turned[2];
        }
        return new ZoneEnvironmentResource.LightGroup(
                lerpByte(a.sunR(), b.sunR(), t),
                lerpByte(a.sunG(), b.sunG(), t),
                lerpByte(a.sunB(), b.sunB(), t),
                lerpByte(a.sunA(), b.sunA(), t),
                moonR, moonG, moonB, moonA,
                lerpByte(a.ambientR(), b.ambientR(), t),
                lerpByte(a.ambientG(), b.ambientG(), t),
                lerpByte(a.ambientB(), b.ambientB(), t),
                lerpByte(a.ambientA(), b.ambientA(), t),
                lerpByte(a.fogR(), b.fogR(), t),
                lerpByte(a.fogG(), b.fogG(), t),
                lerpByte(a.fogB(), b.fogB(), t),
                lerpByte(a.fogA(), b.fogA(), t),
                a.fogEnd() + (b.fogEnd() - a.fogEnd()) * t,
                a.fogStart() + (b.fogStart() - a.fogStart()) * t,
                a.lightScale() + (b.lightScale() - a.lightScale()) * t
        );
    }

    private static int lerpByte(int a, int b, float t) {
        return Math.max(0, Math.min(255, a + (int) ((b - a) * t)));
    }

    private static int[] turnSignedDirection(
            int ar, int ag, int ab,
            int br, int bg, int bb,
            float t
    ) {
        Vector3f p = new Vector3f((byte) ar, (byte) ag, (byte) ab);
        Vector3f n = new Vector3f((byte) br, (byte) bg, (byte) bb);
        if (p.lengthSquared() < 1e-6f || n.lengthSquared() < 1e-6f) {
            return new int[]{ar, ag, ab};
        }
        p.normalize();
        n.normalize();
        Vector3f axis = new Vector3f(p).cross(n);
        Vector3f rotated = new Vector3f(p);
        if (axis.lengthSquared() > 1e-8f) {
            axis.normalize();
            float angle = (float) Math.acos(Math.max(-1.0f, Math.min(1.0f, p.dot(n)))) * t;
            new Quaternionf(new AxisAngle4f(angle, axis)).transform(rotated);
        }
        int qr = ((int) (rotated.x * 127.0f)) & 0xFF;
        int qg = ((int) (rotated.y * 127.0f)) & 0xFF;
        int qb = ((int) (rotated.z * 127.0f)) & 0xFF;
        return new int[]{qr, qg, qb};
    }

    private static ResolvedEnvironment toResolvedEnvironment(
            ZoneEnvironmentResource env,
            String weatherTag,
            float minuteOfDay
    ) {
        Vector3f[] sunMoon = computeSunMoonTravelDirections(minuteOfDay);
        Vector3f sunTravel = sunMoon[0];
        Vector3f moonTravel = sunMoon[1];

        ZoneEnvironmentResource.LightGroup bg = env.bgGroup();
        ZoneEnvironmentResource.LightGroup actor = env.actorGroup();
        boolean indoor = env.isIndoor();

        Vector3f bgSunColor = rgb(bg.sunR(), bg.sunG(), bg.sunB()).mul(bg.lightScale());
        Vector3f bgMoonColor = rgb(bg.moonR(), bg.moonG(), bg.moonB()).mul(bg.lightScale());
        Vector3f actorSunColor = rgb(actor.sunR(), actor.sunG(), actor.sunB()).mul(actor.lightScale());
        Vector3f actorMoonColor = rgb(actor.moonR(), actor.moonG(), actor.moonB()).mul(actor.lightScale());

        Vector3f bgLight0Dir;
        Vector3f bgLight0Color;
        Vector3f bgLight1Dir;
        Vector3f bgLight1Color;
        Vector3f actorLightDir;
        Vector3f actorLightColor;

        if (indoor) {
            Vector3f indoorBgTravel = decodeSignedDir(bg.moonR(), bg.moonG(), bg.moonB());
            Vector3f indoorActorTravel = decodeSignedDir(actor.moonR(), actor.moonG(), actor.moonB());
            bgLight0Dir = new Vector3f(indoorBgTravel).negate();
            bgLight0Color = new Vector3f(bgSunColor);
            bgLight1Dir = new Vector3f(0.0f, -1.0f, 0.0f);
            bgLight1Color = new Vector3f(0.0f);
            actorLightDir = new Vector3f(indoorActorTravel).negate();
            actorLightColor = new Vector3f(actorSunColor);
        } else {
            Vector3f sunToward = new Vector3f(sunTravel).negate();
            Vector3f moonToward = new Vector3f(moonTravel).negate();
            bgLight0Dir = sunToward;
            bgLight0Color = bgSunColor;
            bgLight1Dir = moonToward;
            bgLight1Color = bgMoonColor;

            // Match XIM EnvironmentSection.kt:212-225 smooth dawn/dusk swap (05:55..06:05 and 17:55..18:05)
            float dayBlend;
            if (minuteOfDay < 355.0f) {
                dayBlend = 0.0f;
            } else if (minuteOfDay < 365.0f) {
                dayBlend = (minuteOfDay - 355.0f) / 10.0f;
            } else if (minuteOfDay < 1075.0f) {
                dayBlend = 1.0f;
            } else if (minuteOfDay < 1085.0f) {
                dayBlend = (1085.0f - minuteOfDay) / 10.0f;
            } else {
                dayBlend = 0.0f;
            }
            actorLightDir = (sunTravel.y >= 0.0f) ? new Vector3f(sunToward) : new Vector3f(moonToward);
            actorLightColor = new Vector3f(actorMoonColor).lerp(actorSunColor, dayBlend);
        }

        float fogStart = bg.fogStart();
        float fogEnd = Math.max(fogStart + 1.0f, bg.fogEnd());

        Vector3f[] skyColors = new Vector3f[ZoneEnvironmentResource.SKY_SLICE_COUNT];
        float[] skyStops = new float[ZoneEnvironmentResource.SKY_SLICE_COUNT];
        for (int k = 0; k < ZoneEnvironmentResource.SKY_SLICE_COUNT; k++) {
            skyColors[k] = rgb(env.skyR(k), env.skyG(k), env.skyB(k));
            skyStops[k] = env.skyStop(k);
        }

        return new ResolvedEnvironment(
                indoor,
                minuteOfDay,
                weatherTag,
                sunTravel,
                moonTravel,
                bgLight0Dir,
                bgLight0Color,
                bgLight1Dir,
                bgLight1Color,
                rgb(bg.ambientR(), bg.ambientG(), bg.ambientB()),
                actorLightDir,
                actorLightColor,
                rgb(actor.ambientR(), actor.ambientG(), actor.ambientB()),
                rgb(bg.fogR(), bg.fogG(), bg.fogB()),
                fogStart,
                fogEnd,
                rgb(env.clearR(), env.clearG(), env.clearB()),
                env.drawDist(),
                env.hasDome(),
                Math.max(8, env.spokeCount()),
                env.skyRadius() > 0.0f ? env.skyRadius() : 1200.0f,
                skyColors,
                skyStops
        );
    }

    /**
     * Retail {@code sun_moon(minute)} travel directions (+Y = Down): at noon (720 min) sun travels +Y (straight down
     * from zenith -Y); rises East +X at 06:00 and sets West -X at 18:00; moon is opposite.
     */
    public static Vector3f[] computeSunMoonTravelDirections(float minuteOfDay) {
        float a = (positiveMod(minuteOfDay, MINUTES_PER_DAY) / MINUTES_PER_DAY) * RETAIL_TWO_PI;
        Vector3f moon = new Vector3f((float) Math.sin(a), (float) Math.cos(a), 0.0f);
        float b = a + RETAIL_PI;
        if (b > RETAIL_PI) {
            b -= RETAIL_TWO_PI;
        }
        if (b < -RETAIL_PI) {
            b += RETAIL_TWO_PI;
        }
        Vector3f sun = new Vector3f((float) Math.sin(b), (float) Math.cos(b), 0.0f);
        return new Vector3f[]{sun, moon};
    }

    /** Fallback converter for standalone studio rendering when no zone DAT {@code S2F} resource is loaded. */
    public static ResolvedEnvironment fromFallbackPreset(WeatherPreset preset, float minuteOfDay) {
        WeatherPreset effective = preset != null ? preset : WeatherPreset.FINE;
        float now = positiveMod(minuteOfDay, MINUTES_PER_DAY);
        Vector3f[] sunMoon = computeSunMoonTravelDirections(now);
        Vector3f sunTravel = sunMoon[0];
        Vector3f moonTravel = sunMoon[1];
        boolean night = sunTravel.y < 0.0f;

        Vector3f sunColor = new Vector3f(effective.sunColor()).mul(effective.diffuseMultiplier());
        Vector3f moonColor = new Vector3f(effective.moonColor()).mul(effective.diffuseMultiplier());
        Vector3f ambient = new Vector3f(effective.ambientColor());
        if (night) {
            ambient.mul(0.5f);
        }

        Vector3f[] skyColors = new Vector3f[ZoneEnvironmentResource.SKY_SLICE_COUNT];
        float[] skyStops = new float[ZoneEnvironmentResource.SKY_SLICE_COUNT];
        for (int i = 0; i < ZoneEnvironmentResource.SKY_SLICE_COUNT; i++) {
            WeatherPreset.SkySlice slice = effective.skySlices().get(Math.min(i, effective.skySlices().size() - 1));
            skyColors[i] = new Vector3f(slice.color().x, slice.color().y, slice.color().z);
            if (night) {
                skyColors[i].mul(0.35f);
            }
            skyStops[i] = slice.elevation();
        }

        return new ResolvedEnvironment(
                false,
                now,
                effective.id(),
                sunTravel,
                moonTravel,
                new Vector3f(sunTravel).negate(),
                sunColor,
                new Vector3f(moonTravel).negate(),
                moonColor,
                ambient,
                night ? new Vector3f(moonTravel).negate() : new Vector3f(sunTravel).negate(),
                night ? moonColor : sunColor,
                ambient,
                new Vector3f(effective.fogColor()),
                effective.fogNear(),
                effective.fogFar(),
                new Vector3f(effective.clearColor().x, effective.clearColor().y, effective.clearColor().z),
                600.0f,
                true,
                24,
                1200.0f,
                skyColors,
                skyStops
        );
    }

    private static Vector3f rgb(int r, int g, int b) {
        return new Vector3f(r / 255.0f, g / 255.0f, b / 255.0f);
    }

    private static Vector3f decodeSignedDir(int r, int g, int b) {
        Vector3f dir = new Vector3f((byte) r, (byte) g, (byte) b);
        if (dir.lengthSquared() < 1e-6f) {
            return new Vector3f(0.0f, 1.0f, 0.0f);
        }
        return dir.normalize();
    }

    private static Integer parseHhMmMinutes(String id) {
        if (id == null || id.length() != 4) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        int hh = (id.charAt(0) - '0') * 10 + (id.charAt(1) - '0');
        int mm = (id.charAt(2) - '0') * 10 + (id.charAt(3) - '0');
        if (hh > 24 || mm >= 60) {
            return null;
        }
        return hh * 60 + mm;
    }

    private static float positiveMod(float value, float modulus) {
        float r = value % modulus;
        return r < 0.0f ? r + modulus : r;
    }

    private static String sanitizeId(String id) {
        if (id == null) {
            return "";
        }
        int nul = id.indexOf('\0');
        String trimmed = (nul >= 0 ? id.substring(0, nul) : id).trim();
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
