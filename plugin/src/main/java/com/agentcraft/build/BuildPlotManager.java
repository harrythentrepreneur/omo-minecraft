package com.agentcraft.build;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live {@link BuildPlot}s keyed by agent id. The build studio
 * registers one plot under the mason's id ({@code "mason"}); a {@code build_ops}
 * frame names its target plot by {@code agentId}, and
 * {@link com.agentcraft.bridge.IncomingHandler} looks it up here.
 */
public final class BuildPlotManager {

    private final Map<String, BuildPlot> plots = new ConcurrentHashMap<>();

    public void register(String agentId, BuildPlot plot) {
        BuildPlot prev = plots.put(agentId, plot);
        if (prev != null) prev.cancel(); // a rebuild replaces the old plot
    }

    /** The plot owned by {@code agentId}, or null if none is registered. */
    public BuildPlot forAgent(String agentId) {
        return plots.get(agentId);
    }

    public void remove(String agentId) {
        BuildPlot p = plots.remove(agentId);
        if (p != null) p.cancel();
    }

    /** Cancel every plot's drain timer and forget them all (plugin disable). */
    public void clearAll() {
        for (BuildPlot p : plots.values()) p.cancel();
        plots.clear();
    }
}
