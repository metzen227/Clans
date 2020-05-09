package the_fireplace.clans.forge.compat;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import the_fireplace.clans.Clans;
import the_fireplace.clans.ClansHelper;
import the_fireplace.clans.abstraction.IDynmapCompat;
import the_fireplace.clans.cache.ClanCache;
import the_fireplace.clans.data.ClaimData;
import the_fireplace.clans.model.*;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.regex.Pattern;

public class DynmapCompat implements IDynmapCompat {
    @Override
    public void serverStart() {
        buildDynmapWorldNames();
    }

    @Override
    public void init() {
        MinecraftForge.EVENT_BUS.register(this);
        DynmapCommonAPIListener.register(new DynmapAPIListener());
    }

    private long tickCounter = 0;

    private long m_NextTriggerTickCount = 0;
    private int mapInitAttemptCount = 0;
    private boolean mapInitialized = false;
    private final Set<ClanDimInfo> claimUpdates = Sets.newHashSet();


    @SubscribeEvent
    public void onServerTickEvent(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;

            // Only process these server tickCounter events once a second, there is no need to do this on every tick.
            if (tickCounter % 20 == 0) {
                if (tickCounter >= m_NextTriggerTickCount) {
                    if (mapInitialized) {
                        // Update the claim display in dynmap for the list of teams.
                        if (!claimUpdates.isEmpty()) {
                            for (ClanDimInfo teamDim : claimUpdates)
                                updateClanClaims(teamDim);

                            claimUpdates.clear();
                        }
                    } else {
                        // We can't determine when FTB Claims information will be available so we have to check every so often, for
                        // the most part after the first tickCounter update FTB should be ready to go but we retry a few times before we
                        // consider ourselves initialized.

                        mapInitialized = initializeMap();

                        mapInitAttemptCount++;

                        // After a few attempts to initialize, just consider the system initialized. When no claims exist
                        // we will hit this case.
                        if (mapInitAttemptCount > 10)
                            mapInitialized = true;
                    }

                    m_NextTriggerTickCount = tickCounter + 40;
                }
            }
        }
    }

    /**
     * Method to queue up clan claim event updates to be processed at a later time. Multiple updates for the same
     * clan are combined in to a single update.
     *
     * @param clanDimInfo The clan and dimension the claim update is for.
     */
    @Override
    public void queueClaimEventReceived(ClanDimInfo clanDimInfo) {
        Clans.getMinecraftHelper().getLogger().debug("Claim update notification received for clan [{}] in Dimension [{}], total queued events [{}]", clanDimInfo.getClanIdString(), clanDimInfo.getDim(), claimUpdates.size());

        if(Objects.requireNonNull(ClanCache.getClanById(UUID.fromString(clanDimInfo.getClanIdString()))).isVisibleOnDynmap())
            claimUpdates.add(clanDimInfo);
    }

    /**
     * Updates all the claims in Dynamp for the specified clan in the specified dimension.
     * @param clanDimInfo The clan and dimension to update claims for.
     */
    private void updateClanClaims(ClanDimInfo clanDimInfo) {
        long startTimeNS;
        long totalChunks;
        long totalGroups;

        startTimeNS = System.nanoTime();
        Clans.getMinecraftHelper().getLogger().trace("Claim update started for clan [{}] in Dimension [{}]", clanDimInfo.getClanIdString(), clanDimInfo.getDim());

        Set<ChunkPosition> teamClaimsList = Sets.newConcurrentHashSet(ClaimData.getClaimedChunks(UUID.fromString(clanDimInfo.getClanIdString())));//new set to prevent cache from getting removed from the chunk cache
        totalChunks = teamClaimsList.size();

        // Build a list of groups of claim chunks where the claims are touching each other.
        List<GroupedChunks> groupList = new ArrayList<>();
        if (!teamClaimsList.isEmpty()) {
            for (ChunkPosition pos: teamClaimsList) {
                GroupedChunks group = new GroupedChunks();
                groupList.add(group);

                group.processChunk(pos, teamClaimsList);
            }
        }
        totalGroups = groupList.size();

        // Draw all the team claim markers for the specified dimension.
        clearAllTeamMarkers(clanDimInfo);
        int nIndex = 0;
        for (GroupedChunks group : groupList) {
            List<CoordinatePair> perimeterPoints = group.traceShapePerimeter();

            createAreaMarker(clanDimInfo, nIndex++, perimeterPoints);
        }

        // Make sure we clean up all the object cross references so they can be garbage collected.
        for (GroupedChunks group : groupList)
            group.cleanup();

        long deltaNs = System.nanoTime() - startTimeNS;
        Clans.getMinecraftHelper().getLogger().trace(" --> {} Claim chunks processed.", totalChunks);
        Clans.getMinecraftHelper().getLogger().trace(" --> {} Claim groups detected.", totalGroups);
        Clans.getMinecraftHelper().getLogger().trace(" --> Complete claim update in [{}ns]", deltaNs);

    }

    private boolean initializeMap() {
        Set<ClanDimInfo> teamDimList = Sets.newHashSet();

        for(Clan clan: ClaimData.clansWithClaims()) {
            List<Integer> addedDims = Lists.newArrayList();
            for(ChunkPosition chunk: ClaimData.getClaimedChunks(clan.getId()))
                if(!addedDims.contains(chunk.getDim())) {
                    teamDimList.add(new ClanDimInfo(clan, chunk.getDim()));
                    addedDims.add(chunk.getDim());
                }
        }

        for (ClanDimInfo teamDim : teamDimList)
            queueClaimEventReceived(teamDim);

        return !teamDimList.isEmpty();
    }


    private MarkerAPI dynmapMarkerApi = null;
    private MarkerSet dynmapMarkerSet = null;
    private final Map<Integer, String> dimensionNames = new HashMap<>();

    private static final Pattern FORMATTING_COLOR_CODES_PATTERN = Pattern.compile("(?i)\\u00a7[0-9A-FK-OR]");

    private static final String MARKER_SET_ID = "clans.claims.markerset";
    private static final String MARKER_SET_LABEL = "Clans";

    /**
     * This is a call back class which Dynmap will call when it is ready to accept API requests. This is
     * also where we get the API object reference from.
     */
    private class DynmapAPIListener extends DynmapCommonAPIListener {
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
            if (api != null) {
                dynmapMarkerApi = api.getMarkerAPI();

                createDynmapClaimMarkerLayer();
            }
        }
    }

    /**
     * This creates a marker layer in Dynmap for the claims to be displayed on.
     */
    private void createDynmapClaimMarkerLayer() {
        // Create / update a Dynmap Layer for claims
        dynmapMarkerSet = dynmapMarkerApi.getMarkerSet(MARKER_SET_ID);

        if(dynmapMarkerSet == null)
            dynmapMarkerSet = dynmapMarkerApi.createMarkerSet(MARKER_SET_ID, MARKER_SET_LABEL, null, false);
        else
            dynmapMarkerSet.setMarkerSetLabel(MARKER_SET_LABEL);
    }

    /**
     * This creates a single claim marker in Dynmap.
     * @param clanDimInfo Defines the clan and dimension this claim marker is for
     * @param groupIndex Defines the index number for how many claims this team has
     * @param perimeterPoints A list of X Z points representing the perimeter of the claim to draw.
     */
    public void createAreaMarker(ClanDimInfo clanDimInfo, int groupIndex, List<CoordinatePair> perimeterPoints) {
        if (dynmapMarkerSet != null) {
            String worldName = getWorldName(clanDimInfo.getDim());
            String markerID = worldName + "_" + clanDimInfo.getClanIdString() + "_" + groupIndex;

            double[] xList = new double[perimeterPoints.size()];
            double[] zList = new double[perimeterPoints.size()];

            for (int index = 0; index < perimeterPoints.size(); index++) {
                xList[index] = perimeterPoints.get(index).getX();
                zList[index] = perimeterPoints.get(index).getY();
            }

            // Build the cache going in to the Dynmap tooltip
            StringBuilder stToolTip = getTooltipString(clanDimInfo);

            // Create the area marker for the claim
            AreaMarker marker = dynmapMarkerSet.createAreaMarker(markerID, stToolTip.toString(), true, worldName, xList, zList, false);

            // Configure the marker style
            if (marker != null) {
                int nStrokeWeight = ClansHelper.getConfig().getDynmapBorderWeight();
                double dStrokeOpacity = ClansHelper.getConfig().getDynmapBorderOpacity();
                double dFillOpacity = ClansHelper.getConfig().getDynmapFillOpacity();
                int nFillColor = clanDimInfo.getTeamColor();

                marker.setLineStyle(nStrokeWeight, dStrokeOpacity, nFillColor);
                marker.setFillStyle(dFillOpacity, nFillColor);
            } else
                Clans.getMinecraftHelper().getLogger().error("Failed to create Dynmap area marker for claim.");
        } else
            Clans.getMinecraftHelper().getLogger().error("Failed to create Dynmap area marker for claim, Dynmap Marker Set is not available.");
    }

    @Override
    public void refreshTooltip(Clan clan) {
        List<Integer> addedDims = Lists.newArrayList();
        for(ChunkPosition chunk: ClaimData.getClaimedChunks(clan.getId()))
            if(!addedDims.contains(chunk.getDim())) {
                refreshTooltip(new ClanDimInfo(clan, chunk.getDim()));
                addedDims.add(chunk.getDim());
            }
    }

    public void refreshTooltip(ClanDimInfo info) {
        if(dynmapMarkerSet != null) {
            String newTooltip = getTooltipString(info).toString();
            for(AreaMarker marker: dynmapMarkerSet.getAreaMarkers())
                if(marker.getMarkerID().startsWith(getWorldName(info.getDim())+"_"+info.getClanIdString()+"_"))
                    marker.setLabel(newTooltip, true);
        }
    }

    @Nonnull
    public static StringBuilder getTooltipString(ClanDimInfo clanDimInfo) {
        StringBuilder stToolTip = new StringBuilder("<div class=\"infowindow\">");

        stToolTip.append("<div style=\"text-align: center;\"><span style=\"font-weight:bold;\">").append(clanDimInfo.getClanName()).append("</span></div>");

        if (!clanDimInfo.getClanDescription().isEmpty()) {
            stToolTip.append("<div style=\"text-align: center;\"><span>").append(clanDimInfo.getClanDescription()).append("</span></div>");
        }

        Set<UUID> teamMembers = Objects.requireNonNull(ClanCache.getClanById(UUID.fromString(clanDimInfo.getClanIdString()))).getMembers().keySet();

        if (!teamMembers.isEmpty()) {
            stToolTip.append("<br><div style=\"text-align: center;\"><span style=\"font-weight:bold;\"><i>Clan Members</i></span></div>");

            for (UUID member : teamMembers) {
                GameProfile gp = Clans.getMinecraftHelper().getServer().getPlayerProfileCache().getProfileByUUID(member);
                if(gp != null)
                    stToolTip.append("<div style=\"text-align: center;\"><span>").append(stripColorCodes(gp.getName())).append("</span></div>");
            }
        }

        stToolTip.append("</div>");
        return stToolTip;
    }

    @Override
    public void clearAllTeamMarkers(Clan clan) {
        List<Integer> addedDims = Lists.newArrayList();
        for(ChunkPosition chunk: ClaimData.getClaimedChunks(clan.getId()))
            if(!addedDims.contains(chunk.getDim())) {
                clearAllTeamMarkers(new ClanDimInfo(clan, chunk.getDim()));
                addedDims.add(chunk.getDim());
            }
    }

    /**
     * Find all the markers for the specified team and clear them.
     * @param clanDimInfo Name of team and dimension you want to clear the markers for.
     */
    public void clearAllTeamMarkers(ClanDimInfo clanDimInfo) {
        if (dynmapMarkerSet != null) {
            String worldName = getWorldName(clanDimInfo.getDim());

            int nMarkerID = 0;
            AreaMarker areaMarker;
            do {
                String markerID = worldName + "_" + clanDimInfo.getClanIdString() + "_" + nMarkerID;
                areaMarker = dynmapMarkerSet.findAreaMarker(markerID);

                if (areaMarker != null && areaMarker.getWorld().equals(worldName))
                    areaMarker.deleteMarker();

                nMarkerID++;
            } while (areaMarker != null);
        }
    }

    /**
     * Build a list of dimension names which are compatible with how Dynmap makes its names.
     *
     * Note: This method needs to be called prior to any worlds being unloaded.
     */
    public void buildDynmapWorldNames() {
        WorldServer[] worldsList = Clans.getMinecraftHelper().getServer().worlds;

        // This code below follows Dynmap's naming which is required to get mapping between dimensions and worlds
        // to work. As dynmap API takes world strings not dimension numbers.
        for (WorldServer world : worldsList)
            dimensionNames.put(world.provider.getDimension(),  world.getWorldInfo().getWorldName());

        Clans.getMinecraftHelper().getLogger().debug("Building Dynmap compatible world name list");

        for (Map.Entry<Integer, String> entry : dimensionNames.entrySet())
            Clans.getMinecraftHelper().getLogger().debug("  --> Dimension [{}] = {}", entry.getKey(), entry.getValue());
    }

    /**
     * Helper method to return the name of the world based on the dimension ID.
     *
     * @param dim The dimension ID you want the name for
     * @return Returns the string name of the dimension
     */
    private String getWorldName(int dim) {
        String worldName = "";

        if (dimensionNames.containsKey(dim))
            worldName = dimensionNames.get(dim);

        return  worldName;
    }

    /**
     * @param text Text with color codes
     * @return Removes color codes from text strings and returns the raw text
     */
    public static String stripColorCodes(String text) {
        return text.isEmpty() ? text :FORMATTING_COLOR_CODES_PATTERN.matcher(text).replaceAll("");
    }
}
